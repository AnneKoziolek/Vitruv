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

    /**
     * Performs a bidirectional merge between two branches.
     *
     * <p>First attempts A→B (replay A onto B). If indirect conflicts are detected
     * (derived(A) vs user(B)), attempts the reverse direction B→A. If B→A is clean,
     * uses that result. If both directions produce indirect conflicts, escalates to
     * a true blocking conflict.
     *
     * <p>This avoids the inconsistency problem where discarding derived(A) in A→B
     * leaves reactions unexecuted. In B→A, user(B)'s changes are replayed and
     * reactions fire naturally.
     *
     * @param baseSha    common ancestor commit SHA
     * @param branchASha commit SHA of branch A
     * @param branchBSha commit SHA of branch B
     * @return the merge result, with {@link SemanticMergeResult.MergeDirection} indicating
     *         which direction was used
     */
    public SemanticMergeResult mergeBidirectional(String baseSha, String branchASha, String branchBSha)
            throws IOException, GitAPIException {

        LOGGER.info("Bidirectional merge: base={}, A={}, B={}",
                baseSha.substring(0, 7), branchASha.substring(0, 7), branchBSha.substring(0, 7));

        MergeTracer.trace("");
        MergeTracer.section("MERGE TRACE: Bidirectional merge");
        MergeTracer.trace("  Base: " + baseSha.substring(0, 7)
                + "  |  Branch A: " + branchASha.substring(0, 7)
                + "  |  Branch B: " + branchBSha.substring(0, 7));

        // 1. Try forward: replay A onto B (ours=B, theirs=A)
        MergeTracer.trace("[BIDIR] Step 1: Attempting forward merge (A→B)...");
        SemanticMergeResult forwardResult = merge(baseSha, branchBSha, branchASha);

        // 2. If direct conflicts with no resolver, return immediately
        if (!forwardResult.isSuccess()) {
            MergeTracer.trace("[BIDIR] Forward merge (A→B) failed with direct conflicts — aborting");
            return forwardResult;
        }

        // 3. Check for indirect conflicts in forward direction
        List<MergeConflict> forwardIndirect = forwardResult.getWarnings().stream()
                .filter(w -> w.getType() == MergeConflict.ConflictType.INDIRECT_CONFLICT)
                .toList();

        if (forwardIndirect.isEmpty()) {
            LOGGER.info("Forward merge (A→B) clean — no indirect conflicts");
            MergeTracer.trace("[BIDIR] Forward merge (A→B) clean — no indirect conflicts");
            MergeTracer.trace("[BIDIR] Using forward result");
            return forwardResult;
        }

        LOGGER.info("Forward merge (A→B) has {} indirect conflict(s) — attempting reverse (B→A)",
                forwardIndirect.size());
        MergeTracer.trace("[BIDIR] Forward merge (A→B) has " + forwardIndirect.size()
                + " indirect conflict(s) — attempting reverse (B→A)");

        // 4. Try reverse: replay B onto A (ours=A, theirs=B)
        //    For the reverse direction, we need to invert the conflict resolution provider
        //    because ours/theirs roles are swapped.
        SemanticMergeEngine reverseEngine = new SemanticMergeEngine(
                repoRoot, specs, interactionProvider,
                invertResolutionProvider(conflictResolutionProvider));
        SemanticMergeResult reverseResult = reverseEngine.merge(baseSha, branchASha, branchBSha);

        // 5. If reverse had direct conflicts (no resolver), return forward result as-is
        //    (direct conflicts are symmetric, so this shouldn't happen if forward succeeded)
        if (!reverseResult.isSuccess()) {
            LOGGER.warn("Reverse merge (B→A) failed with direct conflicts — returning forward result");
            return forwardResult;
        }

        // 6. Check for indirect conflicts in reverse direction
        List<MergeConflict> reverseIndirect = reverseResult.getWarnings().stream()
                .filter(w -> w.getType() == MergeConflict.ConflictType.INDIRECT_CONFLICT)
                .toList();

        if (reverseIndirect.isEmpty()) {
            LOGGER.info("Reverse merge (B→A) clean — using reversed result");
            MergeTracer.trace("[BIDIR] Reverse merge (B→A) clean — using REVERSED result");
            // Return reverse result annotated as REVERSED
            List<MergeConflict> reverseWarnings = reverseResult.getWarnings();
            if (!reverseResult.getAppliedResolutions().isEmpty()) {
                return SemanticMergeResult.successWithResolutions(
                        reverseResult.getAppliedResolutions(),
                        reverseResult.getAppliedChanges(),
                        reverseWarnings,
                        reverseResult.getMergedStateFolder(),
                        SemanticMergeResult.MergeDirection.REVERSED);
            }
            return SemanticMergeResult.success(
                    reverseResult.getAppliedChanges(),
                    reverseWarnings,
                    reverseResult.getMergedStateFolder(),
                    SemanticMergeResult.MergeDirection.REVERSED);
        }

        // 7. Both directions have indirect conflicts — true conflict
        LOGGER.warn("Both directions have indirect conflicts — escalating to true conflict");
        MergeTracer.trace("[BIDIR] Both directions have indirect conflicts → BIDIRECTIONAL_INDIRECT_CONFLICT");
        List<MergeConflict> bidirectionalConflicts = new ArrayList<>();
        for (MergeConflict ic : forwardIndirect) {
            bidirectionalConflicts.add(new MergeConflict(
                    ic.getElementId(),
                    MergeConflict.ConflictType.BIDIRECTIONAL_INDIRECT_CONFLICT,
                    ic.getElementUuid(), ic.getConflictingFeature(),
                    ic.getBaseValue(), ic.getOursValue(), ic.getTheirsValue()));
        }
        for (MergeConflict ic : reverseIndirect) {
            bidirectionalConflicts.add(new MergeConflict(
                    ic.getElementId(),
                    MergeConflict.ConflictType.BIDIRECTIONAL_INDIRECT_CONFLICT,
                    ic.getElementUuid(), ic.getConflictingFeature(),
                    ic.getBaseValue(), ic.getOursValue(), ic.getTheirsValue()));
        }
        return SemanticMergeResult.conflict(bidirectionalConflicts);
    }

    /**
     * Creates an inverting wrapper around a {@link ConflictResolutionProvider} that
     * flips OURS↔THEIRS choices. Used for reverse-direction merges where the
     * ours/theirs roles are swapped.
     */
    private static ConflictResolutionProvider invertResolutionProvider(
            ConflictResolutionProvider provider) {
        if (provider == null) return null;
        return conflicts -> provider.resolve(conflicts).stream()
                .map(r -> new ConflictResolution(r.elementUuid(),
                        r.choice() == ConflictResolution.Choice.OURS
                                ? ConflictResolution.Choice.THEIRS
                                : ConflictResolution.Choice.OURS))
                .toList();
    }

    public SemanticMergeResult merge(String baseSha, String oursSha, String theirsSha)
            throws IOException, GitAPIException {

        LOGGER.info("Semantic merge: base={}, ours={}, theirs={}",
                baseSha.substring(0, 7), oursSha.substring(0, 7), theirsSha.substring(0, 7));

        MergeTracer.trace("");
        MergeTracer.section("MERGE TRACE: Directed merge (replay theirs → ours)");
        MergeTracer.trace("  Base: " + baseSha.substring(0, 7)
                + "  |  Ours (target): " + oursSha.substring(0, 7)
                + "  |  Theirs (source): " + theirsSha.substring(0, 7));

        long mergeStartNanos = System.nanoTime();
        long gitStateExtractionNanos = 0;
        long dtoLoadingNanos = 0;
        long conflictDetectionNanos = 0;
        long replayPhaseNanos = 0;

        GitStateLoader loader = new GitStateLoader(repoRoot);

        // 1. Extract states via JGit TreeWalk
        long phaseStart = System.nanoTime();
        Path baseDir = GitStateLoader.createTempDir("merge-base-");
        Path theirsDir = GitStateLoader.createTempDir("merge-theirs-");
        Path oursDir = GitStateLoader.createTempDir("merge-ours-");

        loader.checkoutStateAtCommit(baseSha, baseDir);
        loader.checkoutStateAtCommit(theirsSha, theirsDir);
        loader.checkoutStateAtCommit(oursSha, oursDir);
        gitStateExtractionNanos = System.nanoTime() - phaseStart;
        LOGGER.info("[TIMING] Git state extraction: {} ms", gitStateExtractionNanos / 1_000_000);

        // 2. Load changelog DTOs
        phaseStart = System.nanoTime();
        List<SemanticChangeLog.ChangeDto> oursDtos = loadAllDtosFromDir(oursDir);
        List<SemanticChangeLog.ChangeDto> theirsDtos = loadAllDtosFromDir(theirsDir);
        dtoLoadingNanos = System.nanoTime() - phaseStart;
        LOGGER.info("Loaded {} ours DTOs, {} theirs DTOs", oursDtos.size(), theirsDtos.size());
        LOGGER.info("[TIMING] DTO loading: {} ms", dtoLoadingNanos / 1_000_000);
        MergeTracer.trace("[LOAD] Loaded " + oursDtos.size() + " ours (target) changelog DTOs, "
                + theirsDtos.size() + " theirs (source) changelog DTOs");
        if (!oursDtos.isEmpty()) {
            MergeTracer.trace("[LOAD] Ours (target branch) changes:");
            for (var dto : oursDtos) {
                MergeTracer.trace("         " + formatChangeDto(dto));
            }
        }
        if (!theirsDtos.isEmpty()) {
            MergeTracer.trace("[LOAD] Theirs (source branch) changes:");
            for (var dto : theirsDtos) {
                MergeTracer.trace("         " + formatChangeDto(dto));
            }
        }

        if (theirsDtos.isEmpty()) {
            LOGGER.info("No theirs changelog DTOs — nothing to replay");
            MergeTracer.trace("[RESULT] No source changes to replay — merge trivially succeeds");
            long totalNanosEarly = System.nanoTime() - mergeStartNanos;
            return SemanticMergeResult.success(List.of(), oursDir)
                    .withTimingStats(new SemanticMergeResult.TimingStats()
                            .gitStateExtraction(gitStateExtractionNanos)
                            .dtoLoading(dtoLoadingNanos)
                            .total(totalNanosEarly));
        }

        // 3. UUID-based conflict detection
        phaseStart = System.nanoTime();
        List<MergeConflict> conflicts = List.of();
        List<ConflictResolution> resolutions = List.of();

        if (!oursDtos.isEmpty() || !theirsDtos.isEmpty()) {
            UuidConflictDetector detector = new UuidConflictDetector();
            conflicts = detector.detectConflicts(oursDtos, theirsDtos);

            if (conflicts.isEmpty()) {
                MergeTracer.trace("[CONFLICT] No direct UUID-based conflicts detected");
            } else {
                MergeTracer.trace("[CONFLICT] Detected " + conflicts.size() + " direct UUID-based conflict(s):");
                for (var c : conflicts) {
                    MergeTracer.trace("           " + formatConflict(c));
                }
            }

            if (!conflicts.isEmpty()) {
                if (conflictResolutionProvider == null) {
                    LOGGER.warn("Merge aborted: {} conflicts", conflicts.size());
                    MergeTracer.trace("");
                    MergeTracer.section("MERGE RESULT: CONFLICT (" + conflicts.size()
                            + " blocking conflict(s), merge aborted)");
                    long totalNanosConflict = System.nanoTime() - mergeStartNanos;
                    conflictDetectionNanos = System.nanoTime() - phaseStart;
                    return SemanticMergeResult.conflict(conflicts)
                            .withTimingStats(new SemanticMergeResult.TimingStats()
                                    .gitStateExtraction(gitStateExtractionNanos)
                                    .dtoLoading(dtoLoadingNanos)
                                    .conflictDetection(conflictDetectionNanos)
                                    .total(totalNanosConflict));
                }
                resolutions = conflictResolutionProvider.resolve(conflicts);
                theirsDtos = filterByResolutions(theirsDtos, conflicts, resolutions);
                MergeTracer.trace("[CONFLICT] Conflicts resolved — " + theirsDtos.size()
                        + " DTOs remaining to replay");
                LOGGER.info("After conflict resolution: {} DTOs to replay", theirsDtos.size());
            }
        }

        conflictDetectionNanos = System.nanoTime() - phaseStart;
        LOGGER.info("[TIMING] Conflict detection: {} ms", conflictDetectionNanos / 1_000_000);

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
        // Use EMF URI format for the target prefix (the deserializer extracts filename
        // from source IDs and prepends this prefix)
        String oursUriPrefix = org.eclipse.emf.common.util.URI.createFileURI(
                oursDir.toAbsolutePath().toString()).toString();

        // 6. Replay each transaction separately (per-transaction restore/reactions)
        //    After each transaction:
        //    - Check indirect conflicts: derived(replay(A)) vs user(B)
        //    - Check warnings: user(A) vs derived(B)
        List<EChange<HierarchicalId>> allApplied = new ArrayList<>();
        List<MergeConflict> indirectConflicts = new ArrayList<>();
        List<MergeConflict> warnings = new ArrayList<>();

        // Collect user-authored footprints from target branch for conflict/warning checks
        Set<String> oursUserFootprints = collectUuidFootprints(oursDtos);

        MergeTracer.trace("[REPLAY] Starting per-transaction replay ("
                + theirsTransactions.size() + " transaction(s))");
        long replayPhaseStart = System.nanoTime();
        try {
            for (int i = 0; i < theirsTransactions.size(); i++) {
                List<SemanticChangeLog.ChangeDto> txnDtos = theirsTransactions.get(i);
                MergeTracer.trace("[REPLAY] ── Transaction " + (i + 1) + "/"
                        + theirsTransactions.size() + " (" + txnDtos.size() + " change(s)) ──");
                for (var dto : txnDtos) {
                    MergeTracer.trace("           replay: " + formatChangeDto(dto));
                }

                // Build UUID-string → EObject map from the VSUM's model elements.
                // Used for element existence checks and value snapshots.
                Map<String, EObject> uuidToElement = buildUuidMap(targetVsum);

                // Check user(A) vs derived(B) warnings BEFORE replay:
                // If this transaction's user changes target elements whose state on B
                // is derived (not in oursDtos), that's a warning.
                // Uses the loaded VSUM to check element existence (not just changelogs).
                int warningsBefore = warnings.size();
                warnings.addAll(detectUserVsDerivedWarnings(
                        txnDtos, oursUserFootprints, uuidToElement));
                int newWarnings = warnings.size() - warningsBefore;
                if (newWarnings > 0) {
                    MergeTracer.trace("           [WARNING] " + newWarnings
                            + " USER_VS_DERIVED_WARNING(s) detected before replay:");
                    for (int w = warningsBefore; w < warnings.size(); w++) {
                        MergeTracer.trace("             → " + formatConflict(warnings.get(w)));
                    }
                }

                // Snapshot user(B) footprint values BEFORE replay for indirect conflict detection.
                // After replay, any footprint whose value changed was overwritten by derived(A).
                Map<String, Object> preReplayValues = snapshotUserFootprintValues(
                        oursUserFootprints, uuidToElement);

                // Fresh deserializer per transaction (resets cache ID counter)
                ChangeDtoDeserializer deserializer =
                        new ChangeDtoDeserializer(null, oursUriPrefix);
                List<EChange<HierarchicalId>> txnChanges = deserializer.deserializeAll(txnDtos);
                if (txnChanges.isEmpty()) continue;

                // Capture derived changes from this transaction's reactions
                DerivedChangeCapture derivedCapture = new DerivedChangeCapture();
                targetVsum.addChangePropagationListener(derivedCapture);

                replayChanges(targetVsum, txnChanges);

                targetVsum.removeChangePropagationListener(derivedCapture);
                allApplied.addAll(txnChanges);

                // Check indirect conflicts AFTER replay: derived(replay(A)) vs user(B)
                // Two approaches — PropagatedChange-based (existing) and snapshot-based (robust fallback):
                List<MergeConflict> txnIndirect = detectIndirectConflicts(
                        derivedCapture.getDerivedChanges(), oursDtos,
                        targetVsum.getUuidResolver());

                // Snapshot-based: compare pre/post replay values for user(B) footprints.
                // Catches derived(A) overwrites that PropagatedChange misses.
                Set<String> theirsDirectFootprints = collectUuidFootprints(txnDtos);
                Map<String, EObject> postReplayMap = buildUuidMap(targetVsum);
                txnIndirect.addAll(detectIndirectConflictsViaSnapshot(
                        preReplayValues, oursUserFootprints, theirsDirectFootprints,
                        postReplayMap));

                // Deduplicate by footprint
                int indirectBefore = indirectConflicts.size();
                Set<String> seen = new HashSet<>();
                for (MergeConflict ic : txnIndirect) {
                    String key = ic.getElementUuid() + "#" + ic.getConflictingFeature();
                    if (seen.add(key)) {
                        indirectConflicts.add(ic);
                    }
                }
                int newIndirect = indirectConflicts.size() - indirectBefore;
                if (newIndirect > 0) {
                    MergeTracer.trace("           [INDIRECT] " + newIndirect
                            + " INDIRECT_CONFLICT(s) detected after replay:");
                    for (int ic = indirectBefore; ic < indirectConflicts.size(); ic++) {
                        MergeTracer.trace("             → " + formatConflict(indirectConflicts.get(ic)));
                    }
                }
                MergeTracer.trace("           [REPLAY] Transaction " + (i + 1) + " complete — "
                        + txnChanges.size() + " changes applied, "
                        + newIndirect + " indirect conflict(s), "
                        + newWarnings + " warning(s)");

                LOGGER.info("Replayed transaction {}/{} ({} changes, {} indirect conflicts, {} warnings)",
                        i + 1, theirsTransactions.size(), txnChanges.size(),
                        indirectConflicts.size(), warnings.size());
            }
        } catch (Exception e) {
            LOGGER.error("Replay failed: {}", e.getMessage(), e);
            targetVsum.dispose();
            throw new IOException("Semantic merge replay failed", e);
        }
        replayPhaseNanos = System.nanoTime() - replayPhaseStart;
        LOGGER.info("[TIMING] Replay phase (all transactions): {} ms",
                replayPhaseNanos / 1_000_000);

        targetVsum.dispose();

        if (!indirectConflicts.isEmpty()) {
            LOGGER.warn("{} indirect conflict(s): derived(replay(A)) vs user(B)",
                    indirectConflicts.size());
        }
        if (!warnings.isEmpty()) {
            LOGGER.info("{} warning(s): user(A) vs derived(B)", warnings.size());
        }

        // Combine all warnings: user(A) vs derived(B) + indirect conflicts.
        // Indirect conflicts are reported as warnings (non-blocking) in a directed merge
        // because the merge direction (A→B) means A's changes take precedence.
        List<MergeConflict> allWarnings = new ArrayList<>(warnings);
        allWarnings.addAll(indirectConflicts);

        long totalNanos = System.nanoTime() - mergeStartNanos;
        long totalMs = totalNanos / 1_000_000;
        LOGGER.info("[TIMING] Total merge: {} ms", totalMs);

        // Build per-phase timing stats
        SemanticMergeResult.TimingStats timingStats = new SemanticMergeResult.TimingStats()
                .gitStateExtraction(gitStateExtractionNanos)
                .dtoLoading(dtoLoadingNanos)
                .conflictDetection(conflictDetectionNanos)
                .replay(replayPhaseNanos)
                .total(totalNanos);

        // Print final result summary
        String statusStr = resolutions.isEmpty() ? "SUCCESS" : "SUCCESS_WITH_RESOLUTIONS";
        MergeTracer.trace("");
        MergeTracer.section("MERGE RESULT: " + statusStr);
        MergeTracer.trace("    Changes applied: " + allApplied.size());
        MergeTracer.trace("    Warnings: " + allWarnings.size());
        if (!allWarnings.isEmpty()) {
            for (var w : allWarnings) {
                MergeTracer.trace("      - " + formatConflict(w));
            }
        }
        MergeTracer.trace("    Conflicts: 0 (blocking)");
        if (!resolutions.isEmpty()) {
            MergeTracer.trace("    Resolutions applied: " + resolutions.size());
        }
        MergeTracer.trace("    Duration: " + totalMs + " ms");

        if (!resolutions.isEmpty()) {
            return SemanticMergeResult.successWithResolutions(
                    resolutions, allApplied, allWarnings, oursDir)
                    .withTimingStats(timingStats);
        }
        return SemanticMergeResult.success(allApplied, allWarnings, oursDir)
                .withTimingStats(timingStats);
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
     * Replays deserialized EChanges onto a target VSUM through a ChangeRecordingView.
     *
     * <p>Changes are applied using EMF's reflective API (eSet, eGet, list.add) rather
     * than ApplyEChangeSwitch (which uses EMF Commands via EditingDomain). Direct
     * reflective calls trigger EMF notifications on the objects' adapters, which the
     * ChangeRecordingView's ChangeRecorder captures. ApplyEChangeSwitch uses ad-hoc
     * EditingDomains that bypass the ResourceSet-level adapters.
     *
     * <p>The ChangeRecordingView captures EMF notifications, and {@code view.commitChanges()}
     * propagates them through the reaction engine, enabling transitive propagation
     * across coupled models.
     */
    @SuppressWarnings("unchecked")
    private void replayChanges(InternalVirtualModel targetVsum,
                                List<EChange<HierarchicalId>> changes) {

        // Create a ChangeRecordingView on all model objects
        var selector = targetVsum.createSelector(
                tools.vitruv.framework.views.ViewTypeFactory.createIdentityMappingViewType("merge-replay"));
        targetVsum.getViewSourceModels().stream()
                .flatMap(r -> r.getContents().stream())
                .forEach(root -> selector.setSelected(root, true));
        var view = selector.createView().withChangeRecordingTrait();

        // Resolve HierarchicalIds using the view's ResourceSet
        ResourceSet viewRs = view.getRootObjects(EObject.class).iterator().next()
                .eResource().getResourceSet();
        var idResolver = tools.vitruv.change.atomic.hid.internal.HierarchicalIdResolver.create(viewRs);

        // Apply each change using EMF reflective API (triggers notifications for ChangeRecorder)
        for (EChange<HierarchicalId> eChange : changes) {
            applyChangeReflectively(eChange, idResolver);
        }

        // Commit: ChangeRecordingView captured EMF notifications → propagateChange → reactions fire
        view.commitChanges();
    }

    /**
     * Applies a deserialized EChange to the view's model using EMF's reflective API.
     * Direct calls to eSet/eGet/list.add trigger proper EMF notifications that the
     * ChangeRecorder captures (unlike ApplyEChangeSwitch which uses EditingDomain Commands).
     */
    @SuppressWarnings("unchecked")
    private void applyChangeReflectively(EChange<HierarchicalId> eChange,
                                          tools.vitruv.change.atomic.hid.internal.HierarchicalIdResolver idResolver) {
        if (eChange instanceof tools.vitruv.change.atomic.eobject.CreateEObject<HierarchicalId> ce) {
            EObject created = EcoreUtil.create(ce.getAffectedEObjectType());
            idResolver.getAndUpdateId(created);

        } else if (eChange instanceof tools.vitruv.change.atomic.feature.reference.InsertEReference<HierarchicalId> ir) {
            EObject container = idResolver.getEObject(ir.getAffectedElement());
            EObject newElement = idResolver.getEObject(ir.getNewValue());
            var list = (List<EObject>) container.eGet(ir.getAffectedFeature());
            if (ir.getIndex() >= 0 && ir.getIndex() <= list.size()) {
                list.add(ir.getIndex(), newElement);
            } else {
                list.add(newElement);
            }

        } else if (eChange instanceof tools.vitruv.change.atomic.feature.attribute.ReplaceSingleValuedEAttribute<HierarchicalId, ?> rsa) {
            EObject element = idResolver.getEObject(rsa.getAffectedElement());
            element.eSet(rsa.getAffectedFeature(), rsa.getNewValue());

        } else if (eChange instanceof tools.vitruv.change.atomic.feature.reference.RemoveEReference<HierarchicalId> rr) {
            EObject container = idResolver.getEObject(rr.getAffectedElement());
            var list = (List<EObject>) container.eGet(rr.getAffectedFeature());
            if (rr.getIndex() >= 0 && rr.getIndex() < list.size()) {
                list.remove(rr.getIndex());
            }

        } else if (eChange instanceof tools.vitruv.change.atomic.eobject.DeleteEObject<HierarchicalId> de) {
            EObject element = idResolver.getEObject(de.getAffectedElement());
            EcoreUtil.remove(element);

        } else if (eChange instanceof tools.vitruv.change.atomic.feature.attribute.InsertEAttributeValue<HierarchicalId, ?> ia) {
            EObject element = idResolver.getEObject(ia.getAffectedElement());
            var list = (List<Object>) element.eGet(ia.getAffectedFeature());
            list.add(ia.getIndex(), ia.getNewValue());

        } else if (eChange instanceof tools.vitruv.change.atomic.root.InsertRootEObject<HierarchicalId> iro) {
            EObject newRoot = idResolver.getEObject(iro.getNewValue());
            idResolver.getResource(org.eclipse.emf.common.util.URI.createURI(iro.getUri()))
                    .getContents().add(iro.getIndex(), newRoot);
        }
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
     * <p>Uses the loaded VSUM's UuidResolver to check element existence in the actual
     * model state, not just the changelog DTOs. This correctly detects elements that
     * were created by reactions (derived state) which don't appear in changelogs.
     *
     * <p>Policy: replay proceeds, source user intent wins, warning is recorded.
     *
     * <p>Implements Section 8.3 of the formalization.
     */
    private List<MergeConflict> detectUserVsDerivedWarnings(
            List<SemanticChangeLog.ChangeDto> theirsTxnDtos,
            Set<String> oursUserFootprints,
            Map<String, EObject> uuidToElement) {

        List<MergeConflict> warnings = new ArrayList<>();

        for (SemanticChangeLog.ChangeDto dto : theirsTxnDtos) {
            if (dto.affectedElementUuid == null || dto.featureName == null) continue;

            String footprint = dto.affectedElementUuid + "#" + dto.featureName;

            // Skip if the footprint IS in ours user changes (that would be a direct conflict,
            // already detected by UuidConflictDetector).
            if (oursUserFootprints.contains(footprint)) continue;

            // Check: does the element exist on the target branch's VSUM?
            // If we can find it in the UUID map, the element exists — its current
            // state was either from the base or derived by reactions on B.
            // Either way, user(A) overwriting it deserves a warning.
            boolean elementExistsOnB = uuidToElement.containsKey(dto.affectedElementUuid);

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
        return warnings;
    }

    /**
     * Builds a map from UUID-string → EObject for all elements in the VSUM's model resources.
     * Used for element existence checks and value snapshots without needing to construct
     * package-private {@code Uuid} objects.
     */
    private Map<String, EObject> buildUuidMap(InternalVirtualModel vsum) {
        Map<String, EObject> map = new java.util.HashMap<>();
        UuidResolver resolver = vsum.getUuidResolver();
        for (var sourceModel : vsum.getViewSourceModels()) {
            for (Resource resource : sourceModel.getResourceSet().getResources()) {
                var it = resource.getAllContents();
                while (it.hasNext()) {
                    EObject obj = it.next();
                    try {
                        String uuidStr = resolver.getUuid(obj).toString();
                        map.put(uuidStr, obj);
                    } catch (IllegalStateException e) {
                        // No UUID for this object (e.g., proxy or transient)
                    }
                }
            }
        }
        return map;
    }

    /**
     * Snapshots the current values of all element+feature pairs in the user(B) footprints.
     * Used for snapshot-based indirect conflict detection after replay.
     */
    private Map<String, Object> snapshotUserFootprintValues(
            Set<String> userFootprints, Map<String, EObject> uuidToElement) {
        Map<String, Object> snapshot = new java.util.HashMap<>();
        for (String footprint : userFootprints) {
            String[] parts = footprint.split("#", 2);
            if (parts.length != 2) continue;
            String uuid = parts[0];
            String featureName = parts[1];
            EObject element = uuidToElement.get(uuid);
            if (element == null) continue;
            var feature = element.eClass().getEStructuralFeature(featureName);
            if (feature == null) continue;
            Object value = element.eGet(feature);
            snapshot.put(footprint, value);
        }
        return snapshot;
    }

    /**
     * Detects indirect conflicts by comparing pre-replay snapshots with post-replay state.
     *
     * <p>If a user(B) footprint's value changed during replay, AND the change was not a
     * direct user(A) change (i.e., it was caused by a reaction), then derived(replay(A))
     * overwrote user(B)'s intent.
     *
     * <p>This is a robust fallback for cases where {@code PropagatedChange.getConsequentialChanges()}
     * doesn't capture the derived changes properly.
     *
     * <p>Implements Section 8.4 of the formalization.
     */
    private List<MergeConflict> detectIndirectConflictsViaSnapshot(
            Map<String, Object> preReplayValues,
            Set<String> oursUserFootprints,
            Set<String> theirsDirectFootprints,
            Map<String, EObject> uuidToElement) {

        List<MergeConflict> conflicts = new ArrayList<>();

        for (Map.Entry<String, Object> entry : preReplayValues.entrySet()) {
            String footprint = entry.getKey();
            Object oldValue = entry.getValue();

            // Skip footprints that were directly changed by user(A) — those are
            // direct conflicts (already detected by UuidConflictDetector).
            if (theirsDirectFootprints.contains(footprint)) continue;

            String[] parts = footprint.split("#", 2);
            if (parts.length != 2) continue;
            String uuid = parts[0];
            String featureName = parts[1];

            EObject element = uuidToElement.get(uuid);
            if (element == null) continue;
            var feature = element.eClass().getEStructuralFeature(featureName);
            if (feature == null) continue;
            Object newValue = element.eGet(feature);

            // Compare: if value changed, derived(A) overwrote user(B)
            if (!java.util.Objects.equals(oldValue, newValue)) {
                conflicts.add(new MergeConflict(
                        uuid, MergeConflict.ConflictType.INDIRECT_CONFLICT,
                        uuid, featureName,
                        String.valueOf(oldValue), String.valueOf(oldValue),
                        String.valueOf(newValue)));
                LOGGER.warn("Indirect conflict (snapshot): derived(replay(A)) " +
                        "changed user(B) value: uuid={}, feature={}, {} → {}",
                        uuid, featureName, oldValue, newValue);
            }
        }
        return conflicts;
    }

    // ═══════════════════════════════════════════════════════════════
    // Trace formatting helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Formats a ChangeDto into a human-readable description for trace output.
     */
    static String formatChangeDto(SemanticChangeLog.ChangeDto dto) {
        String elementDesc = dto.affectedEClassName != null ? dto.affectedEClassName : "?";
        String idShort = dto.affectedElementId != null ? shortenId(dto.affectedElementId) : "";

        return switch (dto.changeType) {
            case "CreateEObject" -> "create " + (dto.affectedEObjectType != null ? dto.affectedEObjectType : elementDesc);
            case "DeleteEObject" -> "delete " + elementDesc + "(" + idShort + ")";
            case "InsertRootEObject" -> "insert root " + (dto.newValueId != null ? shortenId(dto.newValueId) : "")
                    + " into " + shortenUri(dto.resourceUri);
            case "RemoveRootEObject" -> "remove root " + (dto.oldValueId != null ? shortenId(dto.oldValueId) : "")
                    + " from " + shortenUri(dto.resourceUri);
            case "InsertEReference" -> "add " + (dto.newValueId != null ? shortenId(dto.newValueId) : "element")
                    + " to " + elementDesc + "(" + idShort + ")." + dto.featureName
                    + " at index " + dto.index;
            case "RemoveEReference" -> "remove " + (dto.oldValueId != null ? shortenId(dto.oldValueId) : "element")
                    + " from " + elementDesc + "(" + idShort + ")." + dto.featureName;
            case "ReplaceSingleValuedEReference" -> "set reference " + elementDesc + "(" + idShort + ")."
                    + dto.featureName + " → " + (dto.newValueId != null ? shortenId(dto.newValueId) : "null");
            case "ReplaceSingleValuedEAttribute" -> "modify " + elementDesc + "(" + idShort + ")."
                    + dto.featureName + ": " + dto.oldLiteralValue + " → " + dto.newLiteralValue;
            case "InsertEAttributeValue" -> "insert attribute value " + dto.newLiteralValue
                    + " into " + elementDesc + "(" + idShort + ")." + dto.featureName;
            case "RemoveEAttributeValue" -> "remove attribute value " + dto.oldLiteralValue
                    + " from " + elementDesc + "(" + idShort + ")." + dto.featureName;
            default -> dto.changeType + " on " + elementDesc + "(" + idShort + ")";
        };
    }

    /**
     * Formats a MergeConflict into a human-readable description for trace output.
     */
    static String formatConflict(MergeConflict conflict) {
        return switch (conflict.getType()) {
            case MODIFY_MODIFY -> "MODIFY_MODIFY on feature '" + conflict.getConflictingFeature()
                    + "': ours=" + conflict.getOursValue() + ", theirs=" + conflict.getTheirsValue()
                    + (conflict.getBaseValue() != null ? " (base=" + conflict.getBaseValue() + ")" : "");
            case DELETE_MODIFY -> "DELETE_MODIFY: element deleted on one branch, modified on other"
                    + (conflict.getElementUuid() != null ? " [uuid=" + shortenUuid(conflict.getElementUuid()) + "]" : "");
            case MODIFY_DELETE -> "MODIFY_DELETE: element modified on one branch, deleted on other"
                    + (conflict.getElementUuid() != null ? " [uuid=" + shortenUuid(conflict.getElementUuid()) + "]" : "");
            case INDIRECT_CONFLICT -> "INDIRECT_CONFLICT: derived change overwrites user change on feature '"
                    + conflict.getConflictingFeature() + "'"
                    + (conflict.getOursValue() != null ? " (was: " + conflict.getOursValue()
                            + " → became: " + conflict.getTheirsValue() + ")" : "");
            case USER_VS_DERIVED_WARNING -> "USER_VS_DERIVED_WARNING: user(source) overwrites derived(target) on feature '"
                    + conflict.getConflictingFeature() + "'"
                    + (conflict.getTheirsValue() != null ? " → " + conflict.getTheirsValue() : "");
            case BIDIRECTIONAL_INDIRECT_CONFLICT -> "BIDIRECTIONAL_INDIRECT_CONFLICT on feature '"
                    + conflict.getConflictingFeature() + "': both directions have indirect conflicts";
        };
    }

    private static String shortenId(String id) {
        if (id == null) return "";
        // Extract just the fragment part (after #) or the last path segment
        int hash = id.indexOf('#');
        if (hash >= 0) return id.substring(hash);
        int lastSlash = id.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < id.length() - 1) return id.substring(lastSlash);
        return id;
    }

    private static String shortenUri(String uri) {
        if (uri == null) return "";
        int lastSlash = uri.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < uri.length() - 1) return uri.substring(lastSlash + 1);
        return uri;
    }

    private static String shortenUuid(String uuid) {
        if (uuid == null) return "";
        if (uuid.length() > 20) return uuid.substring(0, 20) + "...";
        return uuid;
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
