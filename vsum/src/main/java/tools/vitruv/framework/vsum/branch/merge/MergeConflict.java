package tools.vitruv.framework.vsum.branch.merge;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.hid.HierarchicalId;

/**
 * Represents a semantic conflict detected during three-way merge.
 * A conflict occurs when both branches modify the same model element
 * in incompatible ways since the common ancestor.
 */
public class MergeConflict {

    /** Classification of the conflict. */
    public enum ConflictType {
        /** Both branches modify the same element. */
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

    public MergeConflict(String elementId, ConflictType type,
                         List<EChange<HierarchicalId>> oursChanges,
                         List<EChange<HierarchicalId>> theirsChanges) {
        this.elementId = Objects.requireNonNull(elementId, "elementId must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.oursChanges = Collections.unmodifiableList(List.copyOf(oursChanges));
        this.theirsChanges = Collections.unmodifiableList(List.copyOf(theirsChanges));
    }

    /** The HierarchicalId string identifying the conflicting element. */
    public String getElementId() {
        return elementId;
    }

    public ConflictType getType() {
        return type;
    }

    /** Changes to this element on the target ("ours") branch. */
    public List<EChange<HierarchicalId>> getOursChanges() {
        return oursChanges;
    }

    /** Changes to this element on the source ("theirs") branch. */
    public List<EChange<HierarchicalId>> getTheirsChanges() {
        return theirsChanges;
    }

    @Override
    public String toString() {
        return "MergeConflict{element=%s, type=%s, ours=%d changes, theirs=%d changes}"
                .formatted(elementId, type, oursChanges.size(), theirsChanges.size());
    }
}
