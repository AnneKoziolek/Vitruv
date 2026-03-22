package tools.vitruv.framework.vsum.branch.merge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
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
import tools.vitruv.change.composite.description.PropagatedChange;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.change.composite.description.VitruviusChangeFactory;
import tools.vitruv.change.composite.description.VitruviusChangeResolver;
import tools.vitruv.change.composite.description.VitruviusChangeResolverFactory;
import tools.vitruv.change.composite.propagation.ChangePropagationListener;
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

        // 4. Load changelog DTOs grouped by transaction for per-transaction replay
        List<List<SemanticChangeLog.ChangeDto>> theirsTransactions =
                loadTransactionsFromDir(theirsDir);
        // Apply conflict resolution filtering to each transaction
        if (!resolutions.isEmpty()) {
            final var finalConflicts = conflicts;
            final var finalResolutions = resolutions;
            theirsTransactions = theirsTransactions.stream()
                    .map(txn -> filterByResolutions(txn, finalConflicts, finalResolutions))
                    .filter(txn -> !txn.isEmpty())
                    .toList();
        }

        // 5. Load target VSUM from ours state
        InternalVirtualModel targetVsum = GitStateLoader.loadVsumFromDir(oursDir, specs, interactionProvider);
        String theirsUriPrefix = theirsDir.toAbsolutePath().toString();
        String oursUriPrefix = oursDir.toAbsolutePath().toString();

        // 6. Replay each transaction separately (per-transaction restore/reactions)
        //    After each transaction, check for indirect conflicts:
        //    derived(replay(A)) vs user(B)
        List<EChange<HierarchicalId>> allApplied = new ArrayList<>();
        List<MergeConflict> indirectConflicts = new ArrayList<>();

        try {
            for (int i = 0; i < theirsTransactions.size(); i++) {
                List<SemanticChangeLog.ChangeDto> txnDtos = theirsTransactions.get(i);

                // Fresh deserializer per transaction (resets cache ID counter)
                ChangeDtoDeserializer deserializer =
                        new ChangeDtoDeserializer(theirsUriPrefix, oursUriPrefix);
                List<EChange<HierarchicalId>> txnChanges = deserializer.deserializeAll(txnDtos);
                if (txnChanges.isEmpty()) continue;

                // Capture derived changes from this transaction's reactions
                DerivedChangeCapture derivedCapture = new DerivedChangeCapture();
                targetVsum.addChangePropagationListener(derivedCapture);

                replayChanges(targetVsum, txnChanges);

                targetVsum.removeChangePropagationListener(derivedCapture);
                allApplied.addAll(txnChanges);

                // Check: did reactions overwrite user changes on target branch?
                List<MergeConflict> indirect = detectIndirectConflicts(
                        derivedCapture.getDerivedChanges(), oursDtos);
                indirectConflicts.addAll(indirect);

                LOGGER.info("Replayed transaction {}/{} ({} changes, {} indirect conflicts)",
                        i + 1, theirsTransactions.size(), txnChanges.size(), indirect.size());
            }
        } catch (Exception e) {
            LOGGER.error("Replay failed: {}", e.getMessage(), e);
            targetVsum.dispose();
            throw new IOException("Semantic merge replay failed", e);
        }

        targetVsum.dispose();

        if (!indirectConflicts.isEmpty()) {
            LOGGER.warn("{} indirect conflict(s) detected (derived vs user)", indirectConflicts.size());
        }

        if (!resolutions.isEmpty() || !indirectConflicts.isEmpty()) {
            return SemanticMergeResult.successWithResolutions(resolutions, allApplied, oursDir);
        }
        return SemanticMergeResult.success(allApplied, oursDir);
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
     * Loads ALL changelog DTOs from a directory, flat.
     */
    private List<SemanticChangeLog.ChangeDto> loadAllDtosFromDir(Path dir) throws IOException {
        return loadTransactionsFromDir(dir).stream().flatMap(List::stream).toList();
    }

    /**
     * Loads changelog DTOs grouped by transaction (one list per changelog file).
     * Each file represents one user commit = one transaction.
     */
    private List<List<SemanticChangeLog.ChangeDto>> loadTransactionsFromDir(Path dir) throws IOException {
        Path clDir = dir.resolve(".vitruvius/semantic-changelogs");
        if (!Files.exists(clDir)) return List.of();

        List<List<SemanticChangeLog.ChangeDto>> transactions = new ArrayList<>();
        try (var stream = Files.list(clDir)) {
            for (Path jsonFile : stream.filter(f -> f.toString().endsWith(".changelog.json")).toList()) {
                String shortSha = jsonFile.getFileName().toString().replace(".changelog.json", "");
                List<SemanticChangeLog.ChangeDto> dtos = SemanticChangeLog.loadDtosFrom(dir, shortSha);
                if (!dtos.isEmpty()) {
                    transactions.add(dtos);
                }
            }
        }
        return transactions;
    }

    /**
     * Detects indirect conflicts: where derived changes (reactions) from replaying a
     * source transaction overwrite user-authored changes on the target branch.
     *
     * <p>Implements Section 7.3 of the formalization: derived(replay(A)) vs user(B).
     * Uses EClass#feature as the footprint key since derived changes are EObject-typed.
     */
    private List<MergeConflict> detectIndirectConflicts(
            List<PropagatedChange> derivedChanges,
            List<SemanticChangeLog.ChangeDto> oursDtos) {

        List<MergeConflict> conflicts = new ArrayList<>();

        // Extract write footprint of derived (consequential) changes
        Set<String> derivedFootprint = new HashSet<>();
        for (PropagatedChange pc : derivedChanges) {
            VitruviusChange<EObject> consequential = pc.getConsequentialChanges();
            if (consequential == null || !consequential.containsConcreteChange()) continue;
            for (EChange<EObject> ec : consequential.getEChanges()) {
                if (ec instanceof tools.vitruv.change.atomic.feature.FeatureEChange<EObject, ?> fc) {
                    EObject element = fc.getAffectedElement();
                    String featureName = fc.getAffectedFeature() != null
                            ? fc.getAffectedFeature().getName() : null;
                    if (element != null && featureName != null) {
                        derivedFootprint.add(element.eClass().getName() + "#" + featureName);
                    }
                }
            }
        }

        if (derivedFootprint.isEmpty()) return conflicts;

        // Extract write footprint of target-branch user changes
        Set<String> oursUserFootprint = new HashSet<>();
        for (SemanticChangeLog.ChangeDto dto : oursDtos) {
            if (dto.affectedEClassName != null && dto.featureName != null) {
                oursUserFootprint.add(dto.affectedEClassName + "#" + dto.featureName);
            }
        }

        // Overlap = indirect conflict
        Set<String> overlap = new HashSet<>(derivedFootprint);
        overlap.retainAll(oursUserFootprint);

        for (String fp : overlap) {
            String[] parts = fp.split("#", 2);
            String uuid = oursDtos.stream()
                    .filter(d -> parts[0].equals(d.affectedEClassName)
                            && parts[1].equals(d.featureName))
                    .map(d -> d.affectedElementUuid)
                    .findFirst().orElse(parts[0]);

            conflicts.add(new MergeConflict(
                    uuid, MergeConflict.ConflictType.MODIFY_MODIFY,
                    uuid, parts[1], null, null, null));
            LOGGER.warn("Indirect conflict: derived change overwrites user change " +
                    "on target branch: {}#{}", parts[0], parts[1]);
        }
        return conflicts;
    }

    /**
     * Captures derived (propagated) changes during a single {@code propagateChange()} call.
     * Used for indirect conflict detection after each replayed transaction.
     */
    private static class DerivedChangeCapture implements ChangePropagationListener {
        private final List<PropagatedChange> derivedChanges = new ArrayList<>();

        @Override
        public void startedChangePropagation(VitruviusChange<Uuid> change) { }

        @Override
        public void finishedChangePropagation(Iterable<PropagatedChange> propagatedChanges) {
            for (PropagatedChange pc : propagatedChanges) {
                derivedChanges.add(pc);
            }
        }

        public List<PropagatedChange> getDerivedChanges() { return derivedChanges; }
    }
}
