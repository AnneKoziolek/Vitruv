package tools.vitruv.framework.vsum.branch.merge;

import static edu.kit.ipd.sdq.commons.util.org.eclipse.emf.ecore.resource.ResourceSetUtil.withGlobalFactories;

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
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jgit.api.errors.GitAPIException;

import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.hid.HierarchicalId;
import tools.vitruv.change.interaction.InteractionResultProvider;
import tools.vitruv.change.propagation.ChangePropagationSpecification;
import tools.vitruv.framework.views.ViewTypeFactory;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;

/**
 * Core semantic three-way merge engine.
 *
 * <p>Uses two mechanisms:
 * <ul>
 *   <li><strong>Changelog DTOs + UUIDs</strong> for conflict detection — compares changes
 *       by stable element identity (UUID) to find true semantic conflicts</li>
 *   <li><strong>ChangeRecordingView</strong> for replay — applies the source branch's model
 *       state onto a view of the target VSUM, with fine-grained change recording so that
 *       {@code propagateChange()} fires reactions correctly</li>
 * </ul>
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Extract base/ours/theirs states via JGit TreeWalk</li>
 *   <li>Load changelog DTOs and detect UUID-based conflicts</li>
 *   <li>Load target VSUM from ours state</li>
 *   <li>Create a ChangeRecordingView on the target VSUM</li>
 *   <li>Apply theirs' model state changes to the view (additions, modifications, deletions)</li>
 *   <li>{@code view.commitChanges()} records individual EMF changes and propagates them</li>
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

        // 2. Load changelog DTOs for conflict detection
        List<SemanticChangeLog.ChangeDto> oursDtos = loadAllDtosFromDir(oursDir);
        List<SemanticChangeLog.ChangeDto> theirsDtos = loadAllDtosFromDir(theirsDir);
        LOGGER.info("Loaded {} ours DTOs, {} theirs DTOs", oursDtos.size(), theirsDtos.size());

        if (theirsDtos.isEmpty()) {
            LOGGER.info("No theirs changelog DTOs — checking for model differences");
            // Fall through to view-based merge if there are model file differences
            // but no changelogs (e.g., changelogs weren't captured for this branch)
        }

        // 3. UUID-based conflict detection
        List<MergeConflict> conflicts = List.of();
        List<ConflictResolution> resolutions = List.of();
        Set<String> skipTheirsUuids = Set.of();

        if (!oursDtos.isEmpty() || !theirsDtos.isEmpty()) {
            UuidConflictDetector detector = new UuidConflictDetector();
            conflicts = detector.detectConflicts(oursDtos, theirsDtos);

            if (!conflicts.isEmpty()) {
                if (conflictResolutionProvider == null) {
                    LOGGER.warn("Merge aborted: {} conflicts", conflicts.size());
                    return SemanticMergeResult.conflict(conflicts);
                }
                resolutions = conflictResolutionProvider.resolve(conflicts);
                // Collect UUIDs where user chose OURS (= skip theirs' changes for those elements)
                skipTheirsUuids = resolutions.stream()
                        .filter(r -> r.choice() == ConflictResolution.Choice.OURS)
                        .map(ConflictResolution::elementUuid)
                        .collect(Collectors.toSet());
                LOGGER.info("Resolved {} conflicts ({} keep ours, {} accept theirs)",
                        conflicts.size(), skipTheirsUuids.size(),
                        conflicts.size() - skipTheirsUuids.size());
            }
        }

        // 4. Check if there's anything to merge
        List<String> primaryModelFiles = findPrimaryModelFiles(baseDir);
        if (theirsDtos.isEmpty() && primaryModelFiles.isEmpty()) {
            LOGGER.info("No source changes to merge — nothing to do");
            return SemanticMergeResult.success(List.of(), oursDir);
        }

        // 5. Load target VSUM and apply theirs' changes via ChangeRecordingView
        InternalVirtualModel targetVsum = GitStateLoader.loadVsumFromDir(oursDir, specs, interactionProvider);

        try {
            applyTheirsChanges(targetVsum, baseDir, theirsDir, primaryModelFiles,
                    skipTheirsUuids, theirsDtos);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains("no concrete change")) {
                LOGGER.info("No concrete changes to replay — models are identical");
            } else {
                LOGGER.error("Replay failed: {}", e.getMessage(), e);
                targetVsum.dispose();
                throw new IOException("Semantic merge replay failed", e);
            }
        } catch (Exception e) {
            LOGGER.error("Replay failed: {}", e.getMessage(), e);
            targetVsum.dispose();
            throw new IOException("Semantic merge replay failed", e);
        }

        targetVsum.dispose();

        if (!resolutions.isEmpty()) {
            return SemanticMergeResult.successWithResolutions(resolutions, List.of(), oursDir);
        }
        return SemanticMergeResult.success(List.of(), oursDir);
    }

    /**
     * Applies theirs' changes to the target VSUM via a ChangeRecordingView.
     *
     * <p>For each primary model file, compares base vs theirs to determine what changed:
     * <ul>
     *   <li><strong>New containment children</strong>: deep-copied from theirs into the view</li>
     *   <li><strong>Attribute modifications</strong>: applied from theirs to matching view elements</li>
     *   <li><strong>Deletions</strong>: elements in base but not in theirs are removed from view</li>
     * </ul>
     *
     * <p>Uses a ChangeRecordingView so individual EMF changes are captured and propagated
     * via {@code propagateChange()}, firing reactions correctly.
     *
     * @param skipUuids UUIDs of elements where user chose OURS (skip theirs' changes)
     * @param theirsDtos DTOs for UUID-based element matching
     */
    @SuppressWarnings("unchecked")
    private void applyTheirsChanges(InternalVirtualModel targetVsum,
                                     Path baseDir, Path theirsDir,
                                     List<String> primaryModelFiles,
                                     Set<String> skipUuids,
                                     List<SemanticChangeLog.ChangeDto> theirsDtos) {

        // Create a ChangeRecordingView on the target VSUM
        var selector = targetVsum.createSelector(
                ViewTypeFactory.createIdentityMappingViewType("merge-replay"));
        targetVsum.getViewSourceModels().stream()
                .flatMap(r -> r.getContents().stream())
                .filter(obj -> {
                    String uri = obj.eResource().getURI().toString();
                    return primaryModelFiles.stream().anyMatch(uri::endsWith);
                })
                .forEach(root -> selector.setSelected(root, true));
        // Use ChangeRecordingView for fine-grained change tracking
        var view = selector.createView().withChangeRecordingTrait();

        // Load base and theirs models for comparison
        ResourceSet baseRs = withGlobalFactories(new ResourceSetImpl());
        ResourceSet theirsRs = withGlobalFactories(new ResourceSetImpl());

        for (String modelFile : primaryModelFiles) {
            Path basePath = baseDir.resolve(modelFile);
            Path theirsPath = theirsDir.resolve(modelFile);
            if (!Files.exists(theirsPath)) continue;

            Resource theirsResource = theirsRs.getResource(
                    URI.createFileURI(theirsPath.toAbsolutePath().toString()), true);
            Resource baseResource = Files.exists(basePath)
                    ? baseRs.getResource(URI.createFileURI(basePath.toAbsolutePath().toString()), true)
                    : null;

            EObject theirsRoot = theirsResource.getContents().isEmpty() ? null
                    : theirsResource.getContents().get(0);
            EObject baseRoot = (baseResource != null && !baseResource.getContents().isEmpty())
                    ? baseResource.getContents().get(0) : null;
            if (theirsRoot == null) continue;

            // Find matching view root
            for (var viewRoot : view.getRootObjects(EObject.class)) {
                if (!viewRoot.eResource().getURI().toString().endsWith(modelFile)) continue;

                // Apply theirs' changes to the view root by comparing base vs theirs
                applyDiff(viewRoot, baseRoot, theirsRoot, skipUuids, theirsDtos);
                break;
            }
        }

        // Commit: ChangeRecordingView captures individual EMF operations → propagateChange
        view.commitChanges();
        LOGGER.info("Applied theirs' changes via ChangeRecordingView");
    }

    /**
     * Applies the diff between base and theirs onto the view root.
     * Walks containment references and applies additions, modifications, and deletions.
     */
    @SuppressWarnings("unchecked")
    private void applyDiff(EObject viewRoot, EObject baseRoot, EObject theirsRoot,
                           Set<String> skipUuids, List<SemanticChangeLog.ChangeDto> theirsDtos) {
        if (theirsRoot == null) return;

        // Apply attribute changes from theirs (where theirs differs from base)
        for (var attr : theirsRoot.eClass().getEAllAttributes()) {
            Object theirsVal = theirsRoot.eGet(attr);
            Object baseVal = baseRoot != null ? baseRoot.eGet(attr) : null;
            Object viewVal = viewRoot.eGet(attr);

            // If theirs changed this attribute from base, and it's not a skip UUID
            if (!java.util.Objects.equals(baseVal, theirsVal)) {
                // Check if this change should be skipped (user chose OURS)
                if (!shouldSkip(viewRoot, attr.getName(), skipUuids, theirsDtos)) {
                    viewRoot.eSet(attr, theirsVal);
                }
            }
        }

        // Apply containment reference changes
        for (var ref : theirsRoot.eClass().getEAllContainments()) {
            if (!ref.isMany()) continue;

            var theirsList = (List<EObject>) theirsRoot.eGet(ref);
            var baseList = baseRoot != null ? (List<EObject>) baseRoot.eGet(ref) : List.<EObject>of();
            var viewList = (List<EObject>) viewRoot.eGet(ref);

            // Elements in theirs beyond what base had → new additions
            if (theirsList.size() > baseList.size()) {
                for (int i = baseList.size(); i < theirsList.size(); i++) {
                    EObject newElement = EcoreUtil.copy(theirsList.get(i));
                    viewList.add(newElement);
                    LOGGER.debug("Added element from theirs: {}", newElement);
                }
            }

            // Recurse into existing children for attribute modifications
            int commonSize = Math.min(Math.min(baseList.size(), theirsList.size()), viewList.size());
            for (int i = 0; i < commonSize; i++) {
                applyDiff(viewList.get(i), baseList.get(i), theirsList.get(i), skipUuids, theirsDtos);
            }

            // Elements in base but not in theirs → deletions by theirs
            if (theirsList.size() < baseList.size()) {
                for (int i = baseList.size() - 1; i >= theirsList.size(); i--) {
                    if (i < viewList.size()) {
                        viewList.remove(i);
                        LOGGER.debug("Removed element (deleted by theirs) at index {}", i);
                    }
                }
            }
        }
    }

    /**
     * Checks if a change to the given element should be skipped (user chose OURS).
     */
    private boolean shouldSkip(EObject element, String featureName,
                                Set<String> skipUuids,
                                List<SemanticChangeLog.ChangeDto> theirsDtos) {
        if (skipUuids.isEmpty()) return false;
        // Find the UUID for this element in theirs' DTOs
        for (var dto : theirsDtos) {
            if (dto.featureName != null && dto.featureName.equals(featureName)
                    && dto.affectedElementUuid != null
                    && skipUuids.contains(dto.affectedElementUuid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds primary model files (excluding derived models like .model2).
     */
    private List<String> findPrimaryModelFiles(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(dir::relativize)
                    .map(Path::toString)
                    .filter(f -> f.endsWith(".model") || f.endsWith(".xmi") || f.endsWith(".ecore"))
                    .filter(f -> !f.endsWith(".model2"))
                    .filter(f -> !f.contains("vsum/") && !f.contains(".vitruvius/") && !f.contains(".git/"))
                    .collect(Collectors.toList());
        }
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
