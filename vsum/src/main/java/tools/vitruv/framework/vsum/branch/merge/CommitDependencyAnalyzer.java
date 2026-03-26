package tools.vitruv.framework.vsum.branch.merge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.emf.ecore.EObject;

import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.hid.HierarchicalId;
import tools.vitruv.change.composite.description.PropagatedChange;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.change.interaction.InteractionResultProvider;
import tools.vitruv.change.propagation.ChangePropagationSpecification;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;

/**
 * Computes reaction footprints for individual commits.
 *
 * <p>The <em>reaction footprint</em> of a commit c is the set of UUID#feature pairs that
 * Vitruv's propagation engine (reactions) derives when c is replayed. This differs from
 * the direct footprint (what the user explicitly changed) in that reaction footprints
 * arise from Reaction rules firing after replay, not from user intent.
 *
 * <p>Reaction footprints are state-dependent: the same commit may trigger different
 * reactions depending on what model state existed before it was applied. This class
 * computes an <em>estimate</em> by replaying each commit in isolation on the base state.
 * The estimate is used as a starting point; the iterative algorithm in
 * {@link SemanticMergeEngine#mergeWithInterleaving} refines it via monotone union.
 *
 * <p><strong>Note on reads:</strong> User reads are not tracked. We only track writes
 * (direct changes) and derived writes (reaction footprints). This is a fundamental
 * limitation — we cannot know what model elements a user viewed before making an edit.
 * Treating the entire view open at commit time as a read footprint would be a stricter
 * interpretation, but would cause nearly every commit pair to conflict.
 */
public class CommitDependencyAnalyzer {

    private static final Logger LOGGER = LogManager.getLogger(CommitDependencyAnalyzer.class);

    private final Collection<ChangePropagationSpecification> specs;
    private final InteractionResultProvider interactionProvider;

    public CommitDependencyAnalyzer(Collection<ChangePropagationSpecification> specs,
                                     InteractionResultProvider interactionProvider) {
        this.specs = specs;
        this.interactionProvider = interactionProvider;
    }

    /**
     * Computes the estimated reaction footprint of {@code txnDtos} by replaying the
     * transaction in isolation on a copy of {@code baseDir}.
     *
     * <p>This is an estimate: it captures reactions as they would fire on the base
     * model state, without any other commits applied first.
     *
     * @param txnDtos  the changelog DTOs of the commit to analyse
     * @param baseDir  the directory containing the base model state
     * @return set of UUID#feature strings written by reactions after replaying txnDtos
     */
    public Set<String> computeReactionFootprintOnBase(
            List<SemanticChangeLog.ChangeDto> txnDtos, Path baseDir) throws IOException {

        if (txnDtos.isEmpty()) return Set.of();

        // Clone base dir so we don't modify the original
        Path workDir = GitStateLoader.createTempDir("dep-analysis-");
        SemanticMergeEngine.copyDirectory(baseDir, workDir);

        InternalVirtualModel vsum = GitStateLoader.loadVsumFromDir(workDir, specs, interactionProvider);
        String uriPrefix = org.eclipse.emf.common.util.URI
                .createFileURI(workDir.toAbsolutePath().toString()).toString();

        try {
            ChangeDtoDeserializer deserializer = new ChangeDtoDeserializer(null, uriPrefix);
            List<EChange<HierarchicalId>> changes = deserializer.deserializeAll(txnDtos);
            if (changes.isEmpty()) return Set.of();

            SemanticMergeEngine.DerivedChangeCapture capture =
                    new SemanticMergeEngine.DerivedChangeCapture();
            vsum.addChangePropagationListener(capture);
            SemanticMergeEngine.replayChanges(vsum, changes);
            vsum.removeChangePropagationListener(capture);

            return extractFootprintsFromCapture(capture.getDerivedChanges(), vsum.getUuidResolver());
        } catch (Exception e) {
            LOGGER.warn("Failed to compute reaction footprint on base: {}", e.getMessage());
            return Set.of();
        } finally {
            vsum.dispose();
        }
    }

    /**
     * Extracts reaction footprints from a set of {@link PropagatedChange} objects captured
     * during a replay.
     *
     * @param derivedChanges the propagated changes captured during replay
     * @param uuidResolver   UUID resolver for the VSUM used during replay
     * @return set of UUID#feature strings written by reactions
     */
    public static Set<String> extractFootprintsFromCapture(
            List<PropagatedChange> derivedChanges,
            tools.vitruv.change.atomic.uuid.UuidResolver uuidResolver) {

        Set<String> footprints = new HashSet<>();
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
                try {
                    String uuid = uuidResolver.getUuid(element).toString();
                    footprints.add(uuid + "#" + featureName);
                } catch (IllegalStateException e) {
                    // Element not in resolver (transiently created) — skip
                }
            }
        }
        return footprints;
    }
}
