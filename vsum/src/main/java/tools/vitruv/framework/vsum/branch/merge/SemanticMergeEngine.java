package tools.vitruv.framework.vsum.branch.merge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.jgit.api.errors.GitAPIException;

import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.hid.HierarchicalId;
import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.change.composite.description.VitruviusChangeFactory;
import tools.vitruv.change.composite.description.VitruviusChangeResolver;
import tools.vitruv.change.composite.description.VitruviusChangeResolverFactory;
import tools.vitruv.change.interaction.InteractionResultProvider;
import tools.vitruv.change.propagation.ChangePropagationSpecification;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;

/**
 * Core semantic three-way merge engine.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Extract semantic changes from both branches since the merge base</li>
 *   <li>Detect conflicts (overlapping element modifications)</li>
 *   <li>If no conflicts: load target state into a fresh VSUM and replay source changes</li>
 *   <li>Replay goes through {@code propagateChange()} so reactions fire and derived changes are regenerated</li>
 * </ol>
 *
 * <p>The replay step mirrors the flow in {@code IdentityMappingViewType.commitViewChanges()}:
 * HierarchicalId changes are resolved to EObjects, assigned UUIDs, then propagated.
 */
public class SemanticMergeEngine {

    private static final Logger LOGGER = LogManager.getLogger(SemanticMergeEngine.class);

    private final Path repoRoot;
    private final Collection<ChangePropagationSpecification> specs;
    private final InteractionResultProvider interactionProvider;

    public SemanticMergeEngine(Path repoRoot,
                                Collection<ChangePropagationSpecification> specs,
                                InteractionResultProvider interactionProvider) {
        this.repoRoot = repoRoot;
        this.specs = specs;
        this.interactionProvider = interactionProvider;
    }

    /**
     * Performs a semantic three-way merge.
     *
     * @param baseSha  the merge base (common ancestor) commit SHA
     * @param oursSha  the target branch head ("ours") commit SHA
     * @param theirsSha the source branch head ("theirs") commit SHA
     * @return the merge result (success with applied changes, or conflict report)
     */
    public SemanticMergeResult merge(String baseSha, String oursSha, String theirsSha)
            throws IOException, GitAPIException {

        LOGGER.info("Starting semantic three-way merge: base={}, ours={}, theirs={}",
                baseSha.substring(0, 7), oursSha.substring(0, 7), theirsSha.substring(0, 7));

        ChangeExtractor extractor = new ChangeExtractor(repoRoot);

        // 1. Extract changes from both branches
        List<EChange<HierarchicalId>> theirsChanges = extractor.getChangesBetween(baseSha, theirsSha);
        List<EChange<HierarchicalId>> oursChanges = extractor.getChangesBetween(baseSha, oursSha);

        LOGGER.info("Extracted {} source changes and {} target changes",
                theirsChanges.size(), oursChanges.size());

        if (theirsChanges.isEmpty()) {
            LOGGER.info("No source changes to merge — nothing to do");
            return SemanticMergeResult.success(List.of(), repoRoot);
        }

        // 2. Detect conflicts
        ConflictDetector detector = new ConflictDetector();
        List<MergeConflict> conflicts = detector.detectConflicts(oursChanges, theirsChanges);

        if (!conflicts.isEmpty()) {
            LOGGER.warn("Semantic merge aborted: {} conflicts detected", conflicts.size());
            for (MergeConflict c : conflicts) {
                LOGGER.warn("  Conflict: {}", c);
            }
            return SemanticMergeResult.conflict(conflicts);
        }

        // 3. Load target state into a fresh VSUM
        Path tempDir = GitStateLoader.createTempDir("vitruvius-merge-");
        GitStateLoader loader = new GitStateLoader(repoRoot);
        loader.checkoutStateAtCommit(oursSha, tempDir);

        InternalVirtualModel targetVsum = GitStateLoader.loadVsumFromDir(tempDir, specs, interactionProvider);

        // 4. Replay source changes onto target state
        List<EChange<HierarchicalId>> appliedChanges = new ArrayList<>();
        try {
            appliedChanges = replayChanges(targetVsum, theirsChanges);
            LOGGER.info("Successfully replayed {} changes onto target state", appliedChanges.size());
        } catch (Exception e) {
            LOGGER.error("Replay failed: {}", e.getMessage(), e);
            targetVsum.dispose();
            throw new IOException("Semantic merge replay failed", e);
        }

        targetVsum.dispose();
        return SemanticMergeResult.success(appliedChanges, tempDir);
    }

    /**
     * Replays HierarchicalId-based changes onto a target VSUM.
     * Follows the same pattern as {@code IdentityMappingViewType.commitViewChanges()}:
     * resolve HierarchicalId → EObject → assign UUID → propagateChange.
     */
    private List<EChange<HierarchicalId>> replayChanges(
            InternalVirtualModel targetVsum,
            List<EChange<HierarchicalId>> changes) {

        ResourceSet resourceSet = targetVsum.getViewSourceModels().iterator().next().getResourceSet();

        // Create resolvers for the target VSUM's ResourceSet
        VitruviusChangeResolver<HierarchicalId> idResolver =
                VitruviusChangeResolverFactory.forHierarchicalIds(resourceSet);
        VitruviusChangeResolver<Uuid> uuidResolver =
                VitruviusChangeResolverFactory.forUuids(targetVsum.getUuidResolver());

        // Wrap all source changes into a single TransactionalChange
        VitruviusChange<HierarchicalId> hidChange =
                VitruviusChangeFactory.getInstance().createTransactionalChange(changes);

        // Resolve HierarchicalId → EObject (and apply changes to model)
        var resolvedChange = idResolver.resolveAndApply(hidChange);

        // Assign UUIDs to the resolved EObjects
        VitruviusChange<Uuid> uuidChange = uuidResolver.assignIds(resolvedChange);

        // Propagate through the VSUM — this fires reactions, maintains correspondences
        targetVsum.propagateChange(uuidChange);

        LOGGER.debug("Replayed {} changes through propagateChange()", changes.size());
        return new ArrayList<>(changes);
    }
}
