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
        CONFLICT,
        /** Merge completed with conflicts that were resolved by the user. */
        SUCCESS_WITH_RESOLUTIONS
    }

    private final Status status;
    private final List<MergeConflict> conflicts;
    private final List<EChange<HierarchicalId>> appliedChanges;
    private final List<ConflictResolution> appliedResolutions;
    private final List<MergeConflict> warnings;
    private final Path mergedStateFolder;

    private SemanticMergeResult(Status status, List<MergeConflict> conflicts,
                                List<EChange<HierarchicalId>> appliedChanges,
                                List<ConflictResolution> appliedResolutions,
                                List<MergeConflict> warnings,
                                Path mergedStateFolder) {
        this.status = status;
        this.conflicts = Collections.unmodifiableList(List.copyOf(conflicts));
        this.appliedChanges = Collections.unmodifiableList(List.copyOf(appliedChanges));
        this.appliedResolutions = appliedResolutions != null
                ? Collections.unmodifiableList(List.copyOf(appliedResolutions)) : List.of();
        this.warnings = warnings != null
                ? Collections.unmodifiableList(List.copyOf(warnings)) : List.of();
        this.mergedStateFolder = mergedStateFolder;
    }

    public static SemanticMergeResult success(List<EChange<HierarchicalId>> appliedChanges,
                                               Path mergedStateFolder) {
        return new SemanticMergeResult(Status.SUCCESS, List.of(), appliedChanges, null, null, mergedStateFolder);
    }

    public static SemanticMergeResult success(List<EChange<HierarchicalId>> appliedChanges,
                                               List<MergeConflict> warnings,
                                               Path mergedStateFolder) {
        return new SemanticMergeResult(Status.SUCCESS, List.of(), appliedChanges, null, warnings, mergedStateFolder);
    }

    public static SemanticMergeResult conflict(List<MergeConflict> conflicts) {
        return new SemanticMergeResult(Status.CONFLICT, conflicts, List.of(), null, null, null);
    }

    public static SemanticMergeResult successWithResolutions(
            List<ConflictResolution> resolutions,
            List<EChange<HierarchicalId>> appliedChanges,
            Path mergedStateFolder) {
        return new SemanticMergeResult(Status.SUCCESS_WITH_RESOLUTIONS, List.of(),
                appliedChanges, resolutions, null, mergedStateFolder);
    }

    public static SemanticMergeResult successWithResolutions(
            List<ConflictResolution> resolutions,
            List<EChange<HierarchicalId>> appliedChanges,
            List<MergeConflict> warnings,
            Path mergedStateFolder) {
        return new SemanticMergeResult(Status.SUCCESS_WITH_RESOLUTIONS, List.of(),
                appliedChanges, resolutions, warnings, mergedStateFolder);
    }

    public Status getStatus() { return status; }

    public boolean isSuccess() {
        return status == Status.SUCCESS || status == Status.SUCCESS_WITH_RESOLUTIONS;
    }

    public List<MergeConflict> getConflicts() { return conflicts; }
    public List<EChange<HierarchicalId>> getAppliedChanges() { return appliedChanges; }
    public List<ConflictResolution> getAppliedResolutions() { return appliedResolutions; }
    /** Non-blocking warnings (e.g., user(A) vs derived(B)). */
    public List<MergeConflict> getWarnings() { return warnings; }
    public Path getMergedStateFolder() { return mergedStateFolder; }

    @Override
    public String toString() {
        return switch (status) {
            case SUCCESS -> "SemanticMergeResult{SUCCESS, %d changes applied}".formatted(appliedChanges.size());
            case CONFLICT -> "SemanticMergeResult{CONFLICT, %d conflicts}".formatted(conflicts.size());
            case SUCCESS_WITH_RESOLUTIONS -> "SemanticMergeResult{SUCCESS_WITH_RESOLUTIONS, %d resolutions}"
                    .formatted(appliedResolutions.size());
        };
    }
}
