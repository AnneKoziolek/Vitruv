package tools.vitruv.framework.vsum.branch.merge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import static edu.kit.ipd.sdq.commons.util.org.eclipse.emf.ecore.resource.ResourceSetUtil.withGlobalFactories;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jgit.api.errors.GitAPIException;

import tools.vitruv.change.atomic.uuid.UuidResolver;

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
 * <p>Replays serialized EChange transactions from the source branch onto the target
 * branch's VSUM. The pipeline is:
 * <ol>
 *   <li>Extract base/ours/theirs states via JGit TreeWalk</li>
 *   <li>Load changelog DTOs from extracted temp dirs</li>
 *   <li>UUID-based conflict detection on DTOs</li>
 *   <li>Filter DTOs by conflict resolutions (ours/theirs choice)</li>
 *   <li>Deserialize filtered DTOs into live {@code EChange<HierarchicalId>} objects</li>
 *   <li>Replay: {@code resolveAndApply → assignIds → propagateChange}</li>
 * </ol>
 *
 * <p>The replay step follows the same pipeline as
 * {@code IdentityMappingViewType.commitViewChanges()} (lines 96-109).
 * Reactions fire automatically during {@code propagateChange()}.
 */
public class SemanticMergeEngine {

    private static final Logger LOGGER = LogManager.getLogger(SemanticMergeEngine.class);

    private final Path repoRoot;
    private final Collection<ChangePropagationSpecification> specs;
    private final InteractionResultProvider interactionProvider;
    private final ConflictResolutionProvider conflictResolutionProvider;

    public SemanticMergeEngine(Path repoRoot,
                                Collection<ChangePropagationSpecification> specs,
                                InteractionResultProvider interactionProvider) {
        this(repoRoot, specs, interactionProvider, null);
    }

    public SemanticMergeEngine(Path repoRoot,
                                Collection<ChangePropagationSpecification> specs,
                                InteractionResultProvider interactionProvider,
                                ConflictResolutionProvider conflictResolutionProvider) {
        this.repoRoot = repoRoot;
        this.specs = specs;
        this.interactionProvider = interactionProvider;
        this.conflictResolutionProvider = conflictResolutionProvider;
    }

    public SemanticMergeResult merge(String baseSha, String oursSha, String theirsSha)
            throws IOException, GitAPIException {

        LOGGER.info("Semantic merge: base={}, ours={}, theirs={}",
                baseSha.substring(0, 7), oursSha.substring(0, 7), theirsSha.substring(0, 7));

        GitStateLoader loader = new GitStateLoader(repoRoot);

        // 1. Extract states via JGit TreeWalk
        Path baseDir = GitStateLoader.createTempDir("merge-base-");
        Path theirsDir = GitStateLoader.createTempDir("merge-theirs-");
        Path oursDir = GitStateLoader.createTempDir("merge-ours-");

        loader.checkoutStateAtCommit(baseSha, baseDir);
        loader.checkoutStateAtCommit(theirsSha, theirsDir);
        loader.checkoutStateAtCommit(oursSha, oursDir);

        // 2. Load changelog DTOs
        List<SemanticChangeLog.ChangeDto> oursDtos = loadAllDtosFromDir(oursDir);
        List<SemanticChangeLog.ChangeDto> theirsDtos = loadAllDtosFromDir(theirsDir);
        LOGGER.info("Loaded {} ours DTOs, {} theirs DTOs", oursDtos.size(), theirsDtos.size());

        if (theirsDtos.isEmpty()) {
            LOGGER.info("No theirs changelog DTOs — nothing to replay");
            return SemanticMergeResult.success(List.of(), oursDir);
        }

        // 3. UUID-based conflict detection
        List<MergeConflict> conflicts = List.of();
        List<ConflictResolution> resolutions = List.of();

        if (!oursDtos.isEmpty() || !theirsDtos.isEmpty()) {
            UuidConflictDetector detector = new UuidConflictDetector();
            conflicts = detector.detectConflicts(oursDtos, theirsDtos);

            if (!conflicts.isEmpty()) {
                if (conflictResolutionProvider == null) {
                    LOGGER.warn("Merge aborted: {} conflicts", conflicts.size());
                    return SemanticMergeResult.conflict(conflicts);
                }
                resolutions = conflictResolutionProvider.resolve(conflicts);
                theirsDtos = filterByResolutions(theirsDtos, conflicts, resolutions);
                LOGGER.info("After conflict resolution: {} DTOs to replay", theirsDtos.size());
            }
        }

        // 4. Deserialize DTOs into live EChange<HierarchicalId> objects
        String theirsUriPrefix = theirsDir.toAbsolutePath().toString();
        String oursUriPrefix = oursDir.toAbsolutePath().toString();
        ChangeDtoDeserializer deserializer = new ChangeDtoDeserializer(theirsUriPrefix, oursUriPrefix);
        List<EChange<HierarchicalId>> theirsChanges = deserializer.deserializeAll(theirsDtos);

        LOGGER.info("Deserialized {} EChanges for replay", theirsChanges.size());

        if (theirsChanges.isEmpty()) {
            return !resolutions.isEmpty()
                    ? SemanticMergeResult.successWithResolutions(resolutions, List.of(), oursDir)
                    : SemanticMergeResult.success(List.of(), oursDir);
        }

        // 5. Load target VSUM from ours state
        InternalVirtualModel targetVsum = GitStateLoader.loadVsumFromDir(oursDir, specs, interactionProvider);

        // 6. Replay: resolveAndApply → assignIds → propagateChange
        try {
            replayChanges(targetVsum, theirsChanges);
            LOGGER.info("Successfully replayed {} changes onto target VSUM", theirsChanges.size());
        } catch (Exception e) {
            LOGGER.error("Replay failed: {}", e.getMessage(), e);
            targetVsum.dispose();
            throw new IOException("Semantic merge replay failed", e);
        }

        targetVsum.dispose();

        return !resolutions.isEmpty()
                ? SemanticMergeResult.successWithResolutions(resolutions, theirsChanges, oursDir)
                : SemanticMergeResult.success(theirsChanges, oursDir);
    }

    /**
     * Replays EChange<HierarchicalId> objects onto a target VSUM.
     *
     * <p>Follows the same pattern as {@code IdentityMappingViewType.commitViewChanges()}:
     * <ol>
     *   <li>Create a COPY of the VSUM's model resources into a fresh ResourceSet</li>
     *   <li>Copy UUID mappings from the VSUM's resolver to the copy's resolver</li>
     *   <li>{@code resolveAndApply} changes on the COPY (not the VSUM directly)</li>
     *   <li>{@code assignIds} on the copy to convert EObject→Uuid</li>
     *   <li>{@code propagateChange} on the VSUM (applies changes to real models + fires reactions)</li>
     * </ol>
     *
     * <p>This separation ensures that changes are not double-applied: resolveAndApply
     * modifies the copy, and propagateChange modifies the VSUM.
     */
    private void replayChanges(InternalVirtualModel targetVsum,
                                List<EChange<HierarchicalId>> changes) {

        // Create a copy ResourceSet with copies of the VSUM's model resources
        ResourceSet copyResourceSet = withGlobalFactories(new ResourceSetImpl());
        UuidResolver copyUuidResolver = UuidResolver.create(copyResourceSet);

        // Copy each model resource and map UUIDs
        java.util.Map<Resource, Resource> resourceMapping = new java.util.HashMap<>();
        for (Resource sourceResource : targetVsum.getViewSourceModels()) {
            Resource copyResource = copyResourceSet.createResource(sourceResource.getURI());
            copyResource.getContents().addAll(EcoreUtil.copyAll(sourceResource.getContents()));
            resourceMapping.put(sourceResource, copyResource);
        }
        targetVsum.getUuidResolver().resolveResources(resourceMapping, copyUuidResolver);

        // Create resolvers for the COPY ResourceSet
        VitruviusChangeResolver<HierarchicalId> idResolver =
                VitruviusChangeResolverFactory.forHierarchicalIds(copyResourceSet);
        VitruviusChangeResolver<Uuid> uuidResolver =
                VitruviusChangeResolverFactory.forUuids(copyUuidResolver);

        VitruviusChange<HierarchicalId> change =
                VitruviusChangeFactory.getInstance().createTransactionalChange(changes);

        // Resolve and apply on the COPY (not the VSUM)
        VitruviusChange<EObject> resolved = idResolver.resolveAndApply(change);
        // Assign UUIDs on the COPY
        VitruviusChange<Uuid> uuidChange = uuidResolver.assignIds(resolved);
        // Propagate on the VSUM (applies to real models + fires reactions)
        targetVsum.propagateChange(uuidChange);
    }

    /**
     * Filters theirs' DTOs based on conflict resolutions.
     * For OURS choice: remove the conflicting theirs DTO.
     * For THEIRS choice: keep it (will be replayed).
     */
    private List<SemanticChangeLog.ChangeDto> filterByResolutions(
            List<SemanticChangeLog.ChangeDto> theirsDtos,
            List<MergeConflict> conflicts,
            List<ConflictResolution> resolutions) {

        Set<String> skipUuids = resolutions.stream()
                .filter(r -> r.choice() == ConflictResolution.Choice.OURS)
                .map(ConflictResolution::elementUuid)
                .collect(Collectors.toSet());

        Map<String, String> conflictFeatures = conflicts.stream()
                .filter(c -> c.getConflictingFeature() != null)
                .collect(Collectors.toMap(
                        c -> c.getElementUuid() + "#" + c.getConflictingFeature(),
                        c -> c.getConflictingFeature(),
                        (a, b) -> a));

        return theirsDtos.stream()
                .filter(dto -> {
                    if (dto.affectedElementUuid == null) return true;
                    String key = dto.affectedElementUuid + "#" + dto.featureName;
                    return !(conflictFeatures.containsKey(key)
                            && skipUuids.contains(dto.affectedElementUuid));
                })
                .toList();
    }

    /**
     * Loads ALL changelog DTOs from a directory's .vitruvius/semantic-changelogs/.
     */
    private List<SemanticChangeLog.ChangeDto> loadAllDtosFromDir(Path dir) throws IOException {
        Path clDir = dir.resolve(".vitruvius/semantic-changelogs");
        if (!Files.exists(clDir)) return List.of();

        List<SemanticChangeLog.ChangeDto> allDtos = new ArrayList<>();
        try (var stream = Files.list(clDir)) {
            for (Path jsonFile : stream.filter(f -> f.toString().endsWith(".changelog.json")).toList()) {
                String shortSha = jsonFile.getFileName().toString().replace(".changelog.json", "");
                allDtos.addAll(SemanticChangeLog.loadDtosFrom(dir, shortSha));
            }
        }
        return allDtos;
    }
}
