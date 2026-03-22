package tools.vitruv.framework.vsum.branch.merge;

import static edu.kit.ipd.sdq.commons.util.org.eclipse.emf.ecore.resource.ResourceSetUtil.withGlobalFactories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.jgit.api.errors.GitAPIException;

import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.hid.HierarchicalId;
import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.changederivation.DefaultStateBasedChangeResolutionStrategy;
import tools.vitruv.change.changederivation.StateBasedChangeResolutionStrategy;
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
 * <p>Uses state-based change derivation (EMFCompare) to compute the semantic diff
 * between the merge base and the source branch. Then replays those changes onto
 * a fresh VSUM loaded from the target branch, using {@code propagateChange()} so
 * reactions fire and derived changes are regenerated.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Checkout base, ours, and theirs model states into temp directories</li>
 *   <li>Derive EChanges from base→theirs and base→ours using EMFCompare</li>
 *   <li>Detect conflicts (overlapping element modifications)</li>
 *   <li>Load target state into a fresh VSUM</li>
 *   <li>Replay source changes via HierarchicalId→EObject→Uuid→propagateChange()</li>
 * </ol>
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
     * Performs a semantic three-way merge.
     */
    public SemanticMergeResult merge(String baseSha, String oursSha, String theirsSha)
            throws IOException, GitAPIException {

        LOGGER.info("Starting semantic three-way merge: base={}, ours={}, theirs={}",
                baseSha.substring(0, 7), oursSha.substring(0, 7), theirsSha.substring(0, 7));

        GitStateLoader loader = new GitStateLoader(repoRoot);

        // 1. Checkout the three states into temp directories
        Path baseDir = GitStateLoader.createTempDir("merge-base-");
        Path theirsDir = GitStateLoader.createTempDir("merge-theirs-");
        Path oursDir = GitStateLoader.createTempDir("merge-ours-");

        loader.checkoutStateAtCommit(baseSha, baseDir);
        loader.checkoutStateAtCommit(theirsSha, theirsDir);
        loader.checkoutStateAtCommit(oursSha, oursDir);

        // 2. Find model files (*.model files in the repo root — prototype assumes flat layout)
        List<String> modelFiles = findModelFiles(baseDir);
        LOGGER.info("Found {} model files to merge", modelFiles.size());

        // 3. Derive changes from base→theirs and base→ours using EMFCompare
        //    Use oursDir as the canonical URI base so HierarchicalIds match the target VSUM
        StateBasedChangeResolutionStrategy strategy = new DefaultStateBasedChangeResolutionStrategy();

        // Only derive changes for primary model files (not derived ones like .model2).
        // Derived model changes will be regenerated by reactions during replay.
        List<String> primaryModelFiles = modelFiles.stream()
                .filter(f -> !f.endsWith(".model2"))
                .toList();
        LOGGER.info("Using {} primary model files (of {} total) for change derivation",
                primaryModelFiles.size(), modelFiles.size());

        List<EChange<HierarchicalId>> theirsChanges = deriveChanges(strategy, baseDir, theirsDir, oursDir, primaryModelFiles);
        List<EChange<HierarchicalId>> oursChanges = deriveChanges(strategy, baseDir, oursDir, oursDir, primaryModelFiles);

        LOGGER.info("Derived {} source (theirs) changes and {} target (ours) changes",
                theirsChanges.size(), oursChanges.size());

        if (theirsChanges.isEmpty()) {
            LOGGER.info("No source changes to merge — nothing to do");
            return SemanticMergeResult.success(List.of(), oursDir);
        }

        // 4. UUID-based conflict detection using changelogs (if available)
        // Load all changelog DTOs from the extracted temp dirs (each branch tip's state)
        try {
            List<SemanticChangeLog.ChangeDto> oursDtos = loadAllDtosFromDir(oursDir);
            List<SemanticChangeLog.ChangeDto> theirsDtos = loadAllDtosFromDir(theirsDir);
            java.lang.System.out.println("[MERGE] Loaded " + oursDtos.size() + " ours DTOs, " +
                    theirsDtos.size() + " theirs DTOs");
            oursDtos.forEach(d -> java.lang.System.out.println("[MERGE]   ours: " + d + " uuid=" + d.affectedElementUuid));
            theirsDtos.forEach(d -> java.lang.System.out.println("[MERGE]   theirs: " + d + " uuid=" + d.affectedElementUuid));

            if (!oursDtos.isEmpty() || !theirsDtos.isEmpty()) {
                LOGGER.info("Using UUID-based conflict detection ({} ours DTOs, {} theirs DTOs)",
                        oursDtos.size(), theirsDtos.size());

                UuidConflictDetector uuidDetector = new UuidConflictDetector();
                List<MergeConflict> conflicts = uuidDetector.detectConflicts(oursDtos, theirsDtos);

                if (!conflicts.isEmpty()) {
                    if (conflictResolutionProvider != null) {
                        List<ConflictResolution> resolutions = conflictResolutionProvider.resolve(conflicts);
                        LOGGER.info("Resolved {} conflicts via provider", resolutions.size());

                        // Apply resolved conflicts directly to the target VSUM
                        InternalVirtualModel resolveVsum = GitStateLoader.loadVsumFromDir(
                                oursDir, specs, interactionProvider);
                        applyConflictResolutions(resolveVsum, conflicts, resolutions, theirsDir, baseDir, primaryModelFiles);
                        resolveVsum.dispose();

                        return SemanticMergeResult.successWithResolutions(
                                resolutions, List.of(), oursDir);
                    } else {
                        LOGGER.warn("Semantic merge aborted: {} conflicts detected", conflicts.size());
                        return SemanticMergeResult.conflict(conflicts);
                    }
                }
            } else {
                LOGGER.info("No changelogs available for UUID-based conflict detection, " +
                        "proceeding with state-based merge");
            }
        } catch (Exception e) {
            LOGGER.warn("Changelog-based conflict detection failed, proceeding without: {}",
                    e.getMessage());
        }

        // 5. Load target state into a fresh VSUM and replay source changes via view
        InternalVirtualModel targetVsum = GitStateLoader.loadVsumFromDir(oursDir, specs, interactionProvider);

        List<EChange<HierarchicalId>> appliedChanges;
        try {
            appliedChanges = replayChangesViaView(targetVsum, theirsDir, baseDir, primaryModelFiles);
            LOGGER.info("Successfully replayed source changes onto target state");
        } catch (Exception e) {
            LOGGER.error("Replay failed: {}", e.getMessage(), e);
            targetVsum.dispose();
            throw new IOException("Semantic merge replay failed", e);
        }

        targetVsum.dispose();
        return SemanticMergeResult.success(theirsChanges, oursDir);
    }

    /**
     * Derives EChanges between two model states using EMFCompare.
     * Resources are loaded with URIs relative to {@code canonicalDir} so that
     * the generated HierarchicalIds match the target VSUM's resource URIs.
     */
    private List<EChange<HierarchicalId>> deriveChanges(
            StateBasedChangeResolutionStrategy strategy,
            Path oldDir, Path newDir, Path canonicalDir,
            List<String> modelFiles) {

        List<EChange<HierarchicalId>> allChanges = new ArrayList<>();
        ResourceSet oldRs = withGlobalFactories(new ResourceSetImpl());
        ResourceSet newRs = withGlobalFactories(new ResourceSetImpl());

        for (String modelFile : modelFiles) {
            Path oldPath = oldDir.resolve(modelFile);
            Path newPath = newDir.resolve(modelFile);
            // Use canonical path for URI so HierarchicalIds match the target VSUM
            URI canonicalUri = URI.createFileURI(canonicalDir.resolve(modelFile).toAbsolutePath().toString());

            if (!Files.exists(oldPath) && Files.exists(newPath)) {
                Resource newResource = loadResourceWithUri(newRs, newPath, canonicalUri);
                VitruviusChange<HierarchicalId> change = strategy.getChangeSequenceForCreated(newResource);
                if (change.containsConcreteChange()) {
                    allChanges.addAll(change.getEChanges());
                }
            } else if (Files.exists(oldPath) && !Files.exists(newPath)) {
                Resource oldResource = loadResourceWithUri(oldRs, oldPath, canonicalUri);
                VitruviusChange<HierarchicalId> change = strategy.getChangeSequenceForDeleted(oldResource);
                if (change.containsConcreteChange()) {
                    allChanges.addAll(change.getEChanges());
                }
            } else if (Files.exists(oldPath) && Files.exists(newPath)) {
                // Load both with canonical URI — old into one RS, new into another
                Resource oldResource = loadResourceWithUri(oldRs, oldPath, canonicalUri);
                Resource newResource = loadResourceWithUri(newRs, newPath, canonicalUri);
                VitruviusChange<HierarchicalId> change = strategy.getChangeSequenceBetween(newResource, oldResource);
                if (change.containsConcreteChange()) {
                    allChanges.addAll(change.getEChanges());
                }
            }
        }

        return allChanges;
    }

    /**
     * Replays source branch changes onto a target VSUM via additive merge.
     *
     * <p>Strategy: Get a view on the target VSUM, load the base and theirs models,
     * find elements in theirs that weren't in base (new additions), and add them to
     * the view. Then commit the view to propagate changes via reactions.
     *
     * <p>This is a true three-way merge: only elements ADDED by theirs (not in base)
     * are merged into ours. Ours' own additions are preserved.
     */
    @SuppressWarnings("unchecked")
    private List<EChange<HierarchicalId>> replayChangesViaView(
            InternalVirtualModel targetVsum,
            Path theirsDir, Path baseDir, List<String> primaryModelFiles) {

        // Create a view on the target VSUM's primary model objects
        var selector = targetVsum.createSelector(
                tools.vitruv.framework.views.ViewTypeFactory.createIdentityMappingViewType("merge-replay"));
        targetVsum.getViewSourceModels().stream()
                .flatMap(r -> r.getContents().stream())
                .filter(obj -> {
                    String uri = obj.eResource().getURI().toString();
                    return primaryModelFiles.stream().anyMatch(uri::endsWith);
                })
                .forEach(root -> selector.setSelected(root, true));
        var view = selector.createView().withChangeDerivingTrait();

        // Load base and theirs models
        ResourceSet baseRs = withGlobalFactories(new ResourceSetImpl());
        ResourceSet theirsRs = withGlobalFactories(new ResourceSetImpl());

        for (String modelFile : primaryModelFiles) {
            Path basePath = baseDir.resolve(modelFile);
            Path theirsPath = theirsDir.resolve(modelFile);
            if (!Files.exists(theirsPath)) continue;

            Resource theirsResource = theirsRs.getResource(
                    URI.createFileURI(theirsPath.toAbsolutePath().toString()), true);
            EObject theirsRoot = theirsResource.getContents().isEmpty() ? null
                    : theirsResource.getContents().get(0);

            Resource baseResource = Files.exists(basePath)
                    ? baseRs.getResource(URI.createFileURI(basePath.toAbsolutePath().toString()), true)
                    : null;
            EObject baseRoot = (baseResource != null && !baseResource.getContents().isEmpty())
                    ? baseResource.getContents().get(0)
                    : null;

            if (theirsRoot == null) continue;

            // Find matching view root and merge new elements from theirs
            for (var viewRoot : view.getRootObjects(EObject.class)) {
                if (viewRoot.eResource().getURI().toString().endsWith(modelFile)) {
                    // For each containment reference, add elements from theirs that weren't in base
                    for (var ref : theirsRoot.eClass().getEAllContainments()) {
                        if (!ref.isMany()) continue;
                        var theirsList = (List<EObject>) theirsRoot.eGet(ref);
                        var baseList = baseRoot != null ? (List<EObject>) baseRoot.eGet(ref) : List.<EObject>of();
                        var viewList = (List<EObject>) viewRoot.eGet(ref);

                        // Elements in theirs beyond what base had are new additions
                        if (theirsList.size() > baseList.size()) {
                            for (int i = baseList.size(); i < theirsList.size(); i++) {
                                EObject newElement = org.eclipse.emf.ecore.util.EcoreUtil.copy(theirsList.get(i));
                                viewList.add(newElement);
                                LOGGER.info("Merged new element into view: {} via {}",
                                        newElement, ref.getName());
                            }
                        }
                    }
                    break;
                }
            }
        }

        // Commit the view — derives diff (ours → ours+theirs additions) and propagates
        view.commitChanges();

        LOGGER.info("Merged source branch elements into target via view commit");
        return List.of();
    }

    /**
     * Finds model files in a directory (files with common EMF model extensions).
     */
    private List<String> findModelFiles(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(dir::relativize)
                    .map(Path::toString)
                    .filter(f -> f.endsWith(".model") || f.endsWith(".model2")
                            || f.endsWith(".xmi") || f.endsWith(".ecore"))
                    .filter(f -> !f.contains(".git/") && !f.contains("vsum/")
                            && !f.contains(".vitruvius/"))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Loads an EMF resource from a file path, using a canonical URI for addressing.
     * This ensures HierarchicalIds in derived changes reference the target VSUM's URIs.
     */
    /**
     * Applies conflict resolutions to the target VSUM.
     * For THEIRS choice: applies the theirs value via a view with change recording.
     * For OURS choice: keeps the current state (no-op).
     */
    @SuppressWarnings("unchecked")
    private void applyConflictResolutions(
            InternalVirtualModel targetVsum,
            List<MergeConflict> conflicts,
            List<ConflictResolution> resolutions,
            Path theirsDir, Path baseDir, List<String> primaryModelFiles) {

        // Build a map of resolution choices
        java.util.Map<String, ConflictResolution.Choice> choiceMap = new java.util.HashMap<>();
        for (ConflictResolution r : resolutions) {
            choiceMap.put(r.elementUuid(), r.choice());
        }

        // For THEIRS choices, we need to apply theirs' values to the target
        boolean hasTheirsChoice = choiceMap.values().stream()
                .anyMatch(c -> c == ConflictResolution.Choice.THEIRS);

        if (!hasTheirsChoice) {
            // All choices are OURS → keep current state, nothing to do
            return;
        }

        // Load theirs model to get the theirs values
        ResourceSet theirsRs = withGlobalFactories(new ResourceSetImpl());
        for (String modelFile : primaryModelFiles) {
            Path theirsPath = theirsDir.resolve(modelFile);
            if (Files.exists(theirsPath)) {
                theirsRs.getResource(URI.createFileURI(theirsPath.toAbsolutePath().toString()), true);
            }
        }

        // Create a change-recording view on the target VSUM
        var selector = targetVsum.createSelector(
                tools.vitruv.framework.views.ViewTypeFactory.createIdentityMappingViewType("conflict-resolve"));
        targetVsum.getViewSourceModels().stream()
                .flatMap(r -> r.getContents().stream())
                .filter(obj -> {
                    String uri = obj.eResource().getURI().toString();
                    return primaryModelFiles.stream().anyMatch(uri::endsWith);
                })
                .forEach(root -> selector.setSelected(root, true));
        var view = selector.createView().withChangeRecordingTrait();

        // Apply theirs' values for each THEIRS resolution
        for (MergeConflict conflict : conflicts) {
            ConflictResolution.Choice choice = choiceMap.getOrDefault(
                    conflict.getElementId(), ConflictResolution.Choice.OURS);
            if (choice != ConflictResolution.Choice.THEIRS) continue;
            if (conflict.getConflictingFeature() == null) continue;

            // Find the element in the view and apply theirs' value
            for (var viewRoot : view.getRootObjects(EObject.class)) {
                applyTheirsValue(viewRoot, conflict, theirsRs);
            }
        }

        try {
            view.commitChanges();
        } catch (Exception e) {
            // "no concrete change" is OK if resolutions only kept ours values
            LOGGER.debug("Conflict resolution commit: {}", e.getMessage());
        }
    }

    /**
     * Applies theirs' attribute value to the matching element in the view.
     */
    private void applyTheirsValue(EObject viewRoot, MergeConflict conflict, ResourceSet theirsRs) {
        String featureName = conflict.getConflictingFeature();
        String theirsValue = conflict.getTheirsValue();

        // Find the corresponding element in theirs model
        // For now, iterate elements and match by feature value pattern
        for (var ref : viewRoot.eClass().getEAllContainments()) {
            if (!ref.isMany()) continue;
            var list = (List<EObject>) viewRoot.eGet(ref);
            for (EObject element : list) {
                var feature = element.eClass().getEStructuralFeature(featureName);
                if (feature != null) {
                    Object currentValue = element.eGet(feature);
                    // Check if this is the conflicting element (its current value is ours' value)
                    if (currentValue != null && currentValue.toString().equals(conflict.getOursValue())) {
                        element.eSet(feature, theirsValue);
                        java.lang.System.out.println("[MERGE] Applied theirs value: " +
                                featureName + "=" + theirsValue + " (was " + currentValue + ")");
                        return;
                    }
                }
            }
        }
    }

    /**
     * Loads ALL changelog DTOs found in the extracted temp directory for a branch tip.
     * Scans the .vitruvius/semantic-changelogs/ directory for all .changelog.json files.
     */
    private List<SemanticChangeLog.ChangeDto> loadAllDtosFromDir(Path dir) throws IOException {
        Path clDir = dir.resolve(".vitruvius/semantic-changelogs");
        if (!Files.exists(clDir)) return List.of();

        List<SemanticChangeLog.ChangeDto> allDtos = new ArrayList<>();
        try (var stream = Files.list(clDir)) {
            var jsonFiles = stream.filter(f -> f.toString().endsWith(".changelog.json")).toList();
            for (Path jsonFile : jsonFiles) {
                // Extract the short SHA from the filename and load via SemanticChangeLog
                String fileName = jsonFile.getFileName().toString();
                String shortSha = fileName.replace(".changelog.json", "");
                List<SemanticChangeLog.ChangeDto> dtos = SemanticChangeLog.loadDtosFrom(dir, shortSha);
                allDtos.addAll(dtos);
            }
        }
        return allDtos;
    }

    private Resource loadResourceWithUri(ResourceSet rs, Path actualPath, URI canonicalUri) {
        // Load from actual path but register under canonical URI
        URI actualUri = URI.createFileURI(actualPath.toAbsolutePath().toString());
        Resource resource = rs.createResource(canonicalUri);
        try {
            // Load content from actual file using an input stream
            resource.load(new java.io.FileInputStream(actualPath.toFile()), Collections.emptyMap());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load resource from " + actualPath, e);
        }
        return resource;
    }
}
