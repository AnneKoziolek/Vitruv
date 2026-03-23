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
        //    After each transaction:
        //    - Check indirect conflicts: derived(replay(A)) vs user(B)
        //    - Check warnings: user(A) vs derived(B)
        List<EChange<HierarchicalId>> allApplied = new ArrayList<>();
        List<MergeConflict> indirectConflicts = new ArrayList<>();
        List<MergeConflict> warnings = new ArrayList<>();

        // Collect user-authored footprints from target branch for conflict/warning checks
        Set<String> oursUserFootprints = collectUuidFootprints(oursDtos);

        try {
            for (int i = 0; i < theirsTransactions.size(); i++) {
                List<SemanticChangeLog.ChangeDto> txnDtos = theirsTransactions.get(i);

                // Check user(A) vs derived(B) warnings BEFORE replay:
                // If this transaction's user changes target elements whose state on B
                // is derived (not in oursDtos), that's a warning.
                warnings.addAll(detectUserVsDerivedWarnings(txnDtos, oursUserFootprints));

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

                // Check indirect conflicts AFTER replay: derived(replay(A)) vs user(B)
                // Use the VSUM's UuidResolver to get per-element UUIDs for derived changes
                indirectConflicts.addAll(detectIndirectConflicts(
                        derivedCapture.getDerivedChanges(), oursDtos,
                        targetVsum.getUuidResolver()));

                LOGGER.info("Replayed transaction {}/{} ({} changes, {} indirect conflicts, {} warnings)",
                        i + 1, theirsTransactions.size(), txnChanges.size(),
                        indirectConflicts.size(), warnings.size());
            }
        } catch (Exception e) {
            LOGGER.error("Replay failed: {}", e.getMessage(), e);
            targetVsum.dispose();
            throw new IOException("Semantic merge replay failed", e);
        }

        targetVsum.dispose();

        if (!indirectConflicts.isEmpty()) {
            LOGGER.warn("{} indirect conflict(s): derived(replay(A)) vs user(B)",
                    indirectConflicts.size());
        }
        if (!warnings.isEmpty()) {
            LOGGER.info("{} warning(s): user(A) vs derived(B)", warnings.size());
        }

        List<MergeConflict> allWarnings = new ArrayList<>(warnings);
        if (!resolutions.isEmpty() || !indirectConflicts.isEmpty()) {
            return SemanticMergeResult.successWithResolutions(
                    resolutions, allApplied, allWarnings, oursDir);
        }
        return SemanticMergeResult.success(allApplied, allWarnings, oursDir);
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
    /**
     * Replays deserialized EChanges onto a target VSUM through a ChangeRecordingView,
     * using Vitruv's own {@link tools.vitruv.change.atomic.hid.AtomicEChangeHierarchicalIdResolver}
     * and {@link tools.vitruv.change.atomic.command.internal.ApplyEChangeSwitch} to
     * resolve and apply each change.
     *
     * <p>This reuses the same resolution and application code that Vitruv uses internally
     * (the same path as {@code ChangeDerivingView} and {@code DeltaBasedResource}).
     * The ChangeRecordingView captures the resulting EMF notifications, and
     * {@code view.commitChanges()} propagates them through the reaction engine,
     * enabling transitive propagation across coupled models.
     */
    private void replayChanges(InternalVirtualModel targetVsum,
                                List<EChange<HierarchicalId>> changes) {

        // Create a ChangeRecordingView on all model objects
        var selector = targetVsum.createSelector(
                tools.vitruv.framework.views.ViewTypeFactory.createIdentityMappingViewType("merge-replay"));
        targetVsum.getViewSourceModels().stream()
                .flatMap(r -> r.getContents().stream())
                .forEach(root -> selector.setSelected(root, true));
        var view = selector.createView().withChangeRecordingTrait();

        // Use Vitruv's own AtomicEChangeHierarchicalIdResolver to resolve and apply
        ResourceSet viewRs = view.getRootObjects(EObject.class).iterator().next()
                .eResource().getResourceSet();
        var resolver = new tools.vitruv.change.atomic.hid.AtomicEChangeHierarchicalIdResolver(viewRs);

        // resolveAndApplyForward: resolves HierarchicalId→EObject via HierarchicalIdResolver,
        // then applies via ApplyEChangeSwitch.applyEChange() — the same code path Vitruv uses
        for (EChange<HierarchicalId> eChange : changes) {
            resolver.resolveAndApplyForward(eChange);
        }

        // Commit: ChangeRecordingView captured EMF notifications → propagateChange → reactions fire
        view.commitChanges();
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
     * Collects UUID#feature footprints from changelog DTOs.
     */
    private Set<String> collectUuidFootprints(List<SemanticChangeLog.ChangeDto> dtos) {
        Set<String> footprints = new HashSet<>();
        for (SemanticChangeLog.ChangeDto dto : dtos) {
            if (dto.affectedElementUuid != null && dto.featureName != null) {
                footprints.add(dto.affectedElementUuid + "#" + dto.featureName);
            }
        }
        return footprints;
    }

    /**
     * Detects indirect conflicts: derived(replay(A)) vs user(B).
     *
     * <p>After replaying a source transaction, reactions (restore) produce consequential
     * changes. If any of those derived changes modify an element+feature that the target
     * branch's user explicitly changed, that's an indirect conflict.
     *
     * <p>Uses the VSUM's UuidResolver to get per-element UUIDs for the derived changes,
     * enabling precise element-level (not type-level) conflict detection.
     *
     * <p>Implements Section 7.3 of the formalization.
     */
    private List<MergeConflict> detectIndirectConflicts(
            List<PropagatedChange> derivedChanges,
            List<SemanticChangeLog.ChangeDto> oursDtos,
            tools.vitruv.change.atomic.uuid.UuidResolver uuidResolver) {

        List<MergeConflict> conflicts = new ArrayList<>();

        // Build target-branch user footprints: UUID#feature
        Set<String> oursUserFootprints = collectUuidFootprints(oursDtos);
        if (oursUserFootprints.isEmpty()) return conflicts;

        // Extract derived footprints using UuidResolver for per-element identity
        for (PropagatedChange pc : derivedChanges) {
            VitruviusChange<EObject> consequential = pc.getConsequentialChanges();
            if (consequential == null || !consequential.containsConcreteChange()) continue;

            for (EChange<EObject> ec : consequential.getEChanges()) {
                if (!(ec instanceof tools.vitruv.change.atomic.feature.FeatureEChange<EObject, ?> fc))
                    continue;
                EObject element = fc.getAffectedElement();
                String featureName = fc.getAffectedFeature() != null
                        ? fc.getAffectedFeature().getName() : null;
                if (element == null || featureName == null) continue;

                // Resolve the element's UUID for per-element matching
                String uuid;
                try {
                    uuid = uuidResolver.getUuid(element).toString();
                } catch (IllegalStateException e) {
                    continue; // element not in UUID resolver (e.g., newly created by reaction)
                }

                String footprint = uuid + "#" + featureName;
                if (oursUserFootprints.contains(footprint)) {
                    conflicts.add(new MergeConflict(
                            uuid, MergeConflict.ConflictType.INDIRECT_CONFLICT,
                            uuid, featureName, null, null, null));
                    LOGGER.warn("Indirect conflict: derived change from replay " +
                            "overwrites user change on target: uuid={}, feature={}",
                            uuid, featureName);
                }
            }
        }
        return conflicts;
    }

    /**
     * Detects user(A) vs derived(B) warnings.
     *
     * <p>If a source transaction's user changes modify an element+feature that is NOT
     * in the target branch's user-authored changes (i.e., the target state for that
     * element+feature is derived, not user-authored), that's a warning.
     *
     * <p>Policy: replay proceeds, source user intent wins, warning is recorded.
     *
     * <p>Implements Section 7.2 of the formalization.
     */
    private List<MergeConflict> detectUserVsDerivedWarnings(
            List<SemanticChangeLog.ChangeDto> theirsTxnDtos,
            Set<String> oursUserFootprints) {

        List<MergeConflict> warnings = new ArrayList<>();

        for (SemanticChangeLog.ChangeDto dto : theirsTxnDtos) {
            if (dto.affectedElementUuid == null || dto.featureName == null) continue;

            String footprint = dto.affectedElementUuid + "#" + dto.featureName;

            // If the target branch has NO user-authored change for this element+feature,
            // but the element exists on the target (it came from the base), then
            // any derived state on B for this element was from reactions, not user intent.
            // User(A) overwriting derived(B) is a warning, not a conflict.
            //
            // We can only detect this if the element UUID exists on both branches
            // (from the common ancestor) but the target branch didn't explicitly change it.
            // Skip if the footprint IS in ours user changes (that would be a direct conflict,
            // already detected by UuidConflictDetector).
            if (!oursUserFootprints.contains(footprint)) {
                // Check: does the element exist on the target branch at all?
                // If the UUID appears in ANY ours DTO, the element exists on B.
                boolean elementExistsOnB = oursUserFootprints.stream()
                        .anyMatch(fp -> fp.startsWith(dto.affectedElementUuid + "#"));
                // Only warn if the element exists on B (otherwise it's a pure addition, no warning)
                if (elementExistsOnB) {
                    warnings.add(new MergeConflict(
                            dto.affectedElementUuid,
                            MergeConflict.ConflictType.USER_VS_DERIVED_WARNING,
                            dto.affectedElementUuid, dto.featureName,
                            null, null, String.valueOf(dto.newLiteralValue)));
                    LOGGER.info("Warning: user(A) overwrites derived(B) state: uuid={}, feature={}",
                            dto.affectedElementUuid, dto.featureName);
                }
            }
        }
        return warnings;
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
