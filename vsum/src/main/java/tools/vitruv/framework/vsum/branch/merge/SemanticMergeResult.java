package tools.vitruv.framework.vsum.branch.merge;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.hid.HierarchicalId;

/**
 * Result of a semantic three-way merge operation.
 */
public class SemanticMergeResult {

    public enum Status {
        /** Merge completed without conflicts. */
        SUCCESS,
        /** Merge aborted due to semantic conflicts. */
        CONFLICT
    }

    private final Status status;
    private final List<MergeConflict> conflicts;
    private final List<EChange<HierarchicalId>> appliedChanges;
    private final Path mergedStateFolder;

    private SemanticMergeResult(Status status, List<MergeConflict> conflicts,
                                List<EChange<HierarchicalId>> appliedChanges,
                                Path mergedStateFolder) {
        this.status = status;
        this.conflicts = Collections.unmodifiableList(List.copyOf(conflicts));
        this.appliedChanges = Collections.unmodifiableList(List.copyOf(appliedChanges));
        this.mergedStateFolder = mergedStateFolder;
    }

    public static SemanticMergeResult success(List<EChange<HierarchicalId>> appliedChanges,
                                               Path mergedStateFolder) {
        return new SemanticMergeResult(Status.SUCCESS, List.of(), appliedChanges, mergedStateFolder);
    }

    public static SemanticMergeResult conflict(List<MergeConflict> conflicts) {
        return new SemanticMergeResult(Status.CONFLICT, conflicts, List.of(), null);
    }

    public Status getStatus() {
        return status;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public List<MergeConflict> getConflicts() {
        return conflicts;
    }

    public List<EChange<HierarchicalId>> getAppliedChanges() {
        return appliedChanges;
    }

    /** The folder containing the merged VSUM state. Null if merge failed. */
    public Path getMergedStateFolder() {
        return mergedStateFolder;
    }

    @Override
    public String toString() {
        if (isSuccess()) {
            return "SemanticMergeResult{SUCCESS, %d changes applied}".formatted(appliedChanges.size());
        } else {
            return "SemanticMergeResult{CONFLICT, %d conflicts}".formatted(conflicts.size());
        }
    }
}
