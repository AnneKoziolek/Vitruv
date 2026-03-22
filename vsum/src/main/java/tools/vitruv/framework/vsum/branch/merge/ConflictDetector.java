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
import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.eobject.DeleteEObject;
import tools.vitruv.change.atomic.eobject.EObjectExistenceEChange;
import tools.vitruv.change.atomic.feature.FeatureEChange;
import tools.vitruv.change.atomic.feature.attribute.InsertEAttributeValue;
import tools.vitruv.change.atomic.feature.attribute.ReplaceSingleValuedEAttribute;
import tools.vitruv.change.atomic.feature.reference.InsertEReference;
import tools.vitruv.change.atomic.feature.reference.ReplaceSingleValuedEReference;
import tools.vitruv.change.atomic.hid.HierarchicalId;
import tools.vitruv.change.atomic.feature.reference.RemoveEReference;
import tools.vitruv.change.atomic.root.InsertRootEObject;
import tools.vitruv.change.atomic.root.RemoveRootEObject;
import tools.vitruv.change.atomic.root.RootEChange;

/**
 * Detects semantic conflicts between two sets of EChanges from divergent branches.
 *
 * <p>Non-conflicting operations (both branches can safely do them):
 * <ul>
 *   <li>Both branches insert into the same multi-valued feature (list)</li>
 *   <li>Both branches create new objects</li>
 *   <li>Both branches insert root objects</li>
 * </ul>
 *
 * <p>True conflicts:
 * <ul>
 *   <li>Both branches replace the same single-valued feature with different values</li>
 *   <li>One branch deletes an element the other modifies</li>
 * </ul>
 */
public class ConflictDetector {

    private static final Logger LOGGER = LogManager.getLogger(ConflictDetector.class);

    public List<MergeConflict> detectConflicts(
            List<EChange<HierarchicalId>> oursChanges,
            List<EChange<HierarchicalId>> theirsChanges) {

        // Collect element IDs of newly created objects — changes to these are initialization, not conflicts
        Set<String> oursCreatedElements = collectCreatedElementIds(oursChanges);
        Set<String> theirsCreatedElements = collectCreatedElementIds(theirsChanges);

        // Only consider potentially conflicting changes (not inserts or creates)
        List<EChange<HierarchicalId>> oursConflictable = filterConflictableChanges(oursChanges, oursCreatedElements);
        List<EChange<HierarchicalId>> theirsConflictable = filterConflictableChanges(theirsChanges, theirsCreatedElements);

        Map<String, List<EChange<HierarchicalId>>> oursByElement = groupByElement(oursConflictable);
        Map<String, List<EChange<HierarchicalId>>> theirsByElement = groupByElement(theirsConflictable);

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
            LOGGER.debug("No semantic conflicts detected (overlapping additive changes are safe)");
        }

        return conflicts;
    }

    /**
     * Filters out additive changes that can never conflict:
     * - InsertEReference (adding to a multi-valued reference list)
     * - InsertEAttributeValue (adding to a multi-valued attribute list)
     * - InsertRootEObject (adding a root to a resource)
     * - CreateEObject (creating new objects)
     *
     * These are safe because both branches can independently add to the same list.
     */
    /**
     * Collects element IDs of objects that were created in this change set.
     * Changes to newly created objects are initialization, not conflicts.
     */
    private Set<String> collectCreatedElementIds(List<EChange<HierarchicalId>> changes) {
        Set<String> created = new HashSet<>();
        for (EChange<HierarchicalId> change : changes) {
            if (change instanceof CreateEObject<HierarchicalId> createChange) {
                HierarchicalId id = createChange.getAffectedElement();
                if (id != null) created.add(id.toString());
            }
        }
        return created;
    }

    /**
     * Filters to only potentially conflicting changes:
     * - Single-valued replacements on pre-existing elements
     * - Deletions
     * - Removals from references
     *
     * Excludes changes to elements that were just created (initialization, not conflict).
     */
    private List<EChange<HierarchicalId>> filterConflictableChanges(
            List<EChange<HierarchicalId>> changes, Set<String> createdElementIds) {
        return changes.stream()
                .filter(c -> c instanceof ReplaceSingleValuedEAttribute<?, ?>
                        || c instanceof ReplaceSingleValuedEReference<?>
                        || c instanceof DeleteEObject<?>
                        || c instanceof RemoveEReference<?>)
                .filter(c -> {
                    // Exclude changes to newly created objects
                    if (c instanceof FeatureEChange<HierarchicalId, ?> fc) {
                        HierarchicalId id = fc.getAffectedElement();
                        return id == null || !createdElementIds.contains(id.toString());
                    }
                    return true;
                })
                .toList();
    }

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

    private String extractElementId(EChange<HierarchicalId> change) {
        if (change instanceof FeatureEChange<HierarchicalId, ?> featureChange) {
            HierarchicalId id = featureChange.getAffectedElement();
            // Use element + feature as conflict key so that changes to different
            // features of the same element don't conflict
            String featureName = featureChange.getAffectedFeature() != null
                    ? featureChange.getAffectedFeature().getName() : "";
            return id != null ? id.toString() + "#" + featureName : null;
        } else if (change instanceof EObjectExistenceEChange<HierarchicalId> existenceChange) {
            HierarchicalId id = existenceChange.getAffectedElement();
            return id != null ? id.toString() : null;
        } else if (change instanceof RootEChange<HierarchicalId> rootChange) {
            return rootChange.getUri() + "#" + rootChange.getIndex();
        }
        return null;
    }

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
