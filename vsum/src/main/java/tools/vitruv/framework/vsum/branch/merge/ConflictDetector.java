package tools.vitruv.framework.vsum.branch.merge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.eobject.DeleteEObject;
import tools.vitruv.change.atomic.eobject.EObjectExistenceEChange;
import tools.vitruv.change.atomic.feature.FeatureEChange;
import tools.vitruv.change.atomic.hid.HierarchicalId;
import tools.vitruv.change.atomic.root.RootEChange;

/**
 * Detects semantic conflicts between two sets of EChanges from divergent branches.
 *
 * <p>For the prototype, conflict detection is element-level:
 * if both branches modify the same element (identified by HierarchicalId),
 * a conflict is reported. Special handling for delete-vs-modify.
 */
public class ConflictDetector {

    private static final Logger LOGGER = LogManager.getLogger(ConflictDetector.class);

    /**
     * Detects conflicts between changes from the target ("ours") and source ("theirs") branches.
     *
     * @param oursChanges   changes from the target branch since merge base
     * @param theirsChanges changes from the source branch since merge base
     * @return list of detected conflicts (empty if no conflicts)
     */
    public List<MergeConflict> detectConflicts(
            List<EChange<HierarchicalId>> oursChanges,
            List<EChange<HierarchicalId>> theirsChanges) {

        // Group changes by affected element ID
        Map<String, List<EChange<HierarchicalId>>> oursByElement = groupByElement(oursChanges);
        Map<String, List<EChange<HierarchicalId>>> theirsByElement = groupByElement(theirsChanges);

        // Find overlapping elements
        Set<String> overlapping = new HashSet<>(oursByElement.keySet());
        overlapping.retainAll(theirsByElement.keySet());

        List<MergeConflict> conflicts = new ArrayList<>();
        for (String elementId : overlapping) {
            List<EChange<HierarchicalId>> ours = oursByElement.get(elementId);
            List<EChange<HierarchicalId>> theirs = theirsByElement.get(elementId);

            MergeConflict.ConflictType type = classifyConflict(ours, theirs);
            conflicts.add(new MergeConflict(elementId, type, ours, theirs));
        }

        if (!conflicts.isEmpty()) {
            LOGGER.info("Detected {} semantic conflicts", conflicts.size());
        } else {
            LOGGER.debug("No semantic conflicts detected");
        }

        return conflicts;
    }

    /**
     * Groups EChanges by the HierarchicalId of the affected element.
     */
    private Map<String, List<EChange<HierarchicalId>>> groupByElement(
            List<EChange<HierarchicalId>> changes) {
        Map<String, List<EChange<HierarchicalId>>> result = new HashMap<>();
        for (EChange<HierarchicalId> change : changes) {
            String elementId = extractElementId(change);
            if (elementId != null) {
                result.computeIfAbsent(elementId, k -> new ArrayList<>()).add(change);
            }
        }
        return result;
    }

    /**
     * Extracts the primary affected element identifier from an EChange.
     */
    private String extractElementId(EChange<HierarchicalId> change) {
        if (change instanceof FeatureEChange<HierarchicalId, ?> featureChange) {
            HierarchicalId id = featureChange.getAffectedElement();
            return id != null ? id.toString() : null;
        } else if (change instanceof EObjectExistenceEChange<HierarchicalId> existenceChange) {
            HierarchicalId id = existenceChange.getAffectedElement();
            return id != null ? id.toString() : null;
        } else if (change instanceof RootEChange<HierarchicalId> rootChange) {
            // For root changes, use the resource URI + index as identifier
            return rootChange.getUri() + "#" + rootChange.getIndex();
        }
        return null;
    }

    /**
     * Classifies a conflict based on the change types involved.
     */
    private MergeConflict.ConflictType classifyConflict(
            List<EChange<HierarchicalId>> ours,
            List<EChange<HierarchicalId>> theirs) {
        boolean oursDeletes = ours.stream().anyMatch(c -> c instanceof DeleteEObject<?>);
        boolean theirsDeletes = theirs.stream().anyMatch(c -> c instanceof DeleteEObject<?>);

        if (oursDeletes && !theirsDeletes) {
            return MergeConflict.ConflictType.DELETE_MODIFY;
        } else if (!oursDeletes && theirsDeletes) {
            return MergeConflict.ConflictType.MODIFY_DELETE;
        }
        return MergeConflict.ConflictType.MODIFY_MODIFY;
    }
}
