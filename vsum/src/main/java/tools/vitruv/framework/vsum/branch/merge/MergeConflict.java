package tools.vitruv.framework.vsum.branch.merge;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.hid.HierarchicalId;

/**
 * Represents a semantic conflict detected during three-way merge.
 * A conflict occurs when both branches modify the same model element
 * (identified by UUID) in incompatible ways since the common ancestor.
 */
public class MergeConflict {

    public enum ConflictType {
        /** Both branches modify the same element's attribute(s). */
        MODIFY_MODIFY,
        /** Target branch deletes an element that source branch modifies. */
        DELETE_MODIFY,
        /** Source branch deletes an element that target branch modifies. */
        MODIFY_DELETE
    }

    private final String elementId;
    private final ConflictType type;
    private final List<EChange<HierarchicalId>> oursChanges;
    private final List<EChange<HierarchicalId>> theirsChanges;

    // UUID-based conflict detail fields (populated by UUID conflict detector)
    private final String elementUuid;
    private final String conflictingFeature;
    private final String baseValue;
    private final String oursValue;
    private final String theirsValue;

    /** EChange-based constructor (used by state-based conflict detection). */
    public MergeConflict(String elementId, ConflictType type,
                         List<EChange<HierarchicalId>> oursChanges,
                         List<EChange<HierarchicalId>> theirsChanges) {
        this(elementId, type, oursChanges, theirsChanges, null, null, null, null, null);
    }

    /** UUID-based constructor (used by changelog-based conflict detection). */
    public MergeConflict(String elementId, ConflictType type,
                         String elementUuid, String conflictingFeature,
                         String baseValue, String oursValue, String theirsValue) {
        this(elementId, type, List.of(), List.of(),
                elementUuid, conflictingFeature, baseValue, oursValue, theirsValue);
    }

    private MergeConflict(String elementId, ConflictType type,
                          List<EChange<HierarchicalId>> oursChanges,
                          List<EChange<HierarchicalId>> theirsChanges,
                          String elementUuid, String conflictingFeature,
                          String baseValue, String oursValue, String theirsValue) {
        this.elementId = Objects.requireNonNull(elementId, "elementId must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.oursChanges = Collections.unmodifiableList(List.copyOf(oursChanges));
        this.theirsChanges = Collections.unmodifiableList(List.copyOf(theirsChanges));
        this.elementUuid = elementUuid;
        this.conflictingFeature = conflictingFeature;
        this.baseValue = baseValue;
        this.oursValue = oursValue;
        this.theirsValue = theirsValue;
    }

    public String getElementId() { return elementId; }
    public ConflictType getType() { return type; }
    public List<EChange<HierarchicalId>> getOursChanges() { return oursChanges; }
    public List<EChange<HierarchicalId>> getTheirsChanges() { return theirsChanges; }

    /** UUID of the conflicting element (null if from state-based detection). */
    public String getElementUuid() { return elementUuid; }
    /** Feature name that conflicts (e.g., "name"). */
    public String getConflictingFeature() { return conflictingFeature; }
    /** Value of the feature in the common ancestor. */
    public String getBaseValue() { return baseValue; }
    /** Value of the feature on the target branch. */
    public String getOursValue() { return oursValue; }
    /** Value of the feature on the source branch. */
    public String getTheirsValue() { return theirsValue; }

    @Override
    public String toString() {
        if (elementUuid != null) {
            return "MergeConflict{uuid=%s, type=%s, feature=%s, base='%s', ours='%s', theirs='%s'}"
                    .formatted(elementUuid, type, conflictingFeature, baseValue, oursValue, theirsValue);
        }
        return "MergeConflict{element=%s, type=%s, ours=%d changes, theirs=%d changes}"
                .formatted(elementId, type, oursChanges.size(), theirsChanges.size());
    }
}
