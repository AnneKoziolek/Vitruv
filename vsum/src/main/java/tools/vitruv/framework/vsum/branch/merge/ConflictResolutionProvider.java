package tools.vitruv.framework.vsum.branch.merge;

import java.util.List;

/**
 * Strategy for resolving merge conflicts.
 * Receives a list of conflicts and returns resolutions (ours or theirs per conflict).
 */
@FunctionalInterface
public interface ConflictResolutionProvider {

    List<ConflictResolution> resolve(List<MergeConflict> conflicts);

    /** Always chooses "ours" for all conflicts. */
    static ConflictResolutionProvider chooseAllOurs() {
        return conflicts -> conflicts.stream()
                .map(c -> new ConflictResolution(c.getElementId(),
                        ConflictResolution.Choice.OURS))
                .toList();
    }

    /** Always chooses "theirs" for all conflicts. */
    static ConflictResolutionProvider chooseAllTheirs() {
        return conflicts -> conflicts.stream()
                .map(c -> new ConflictResolution(c.getElementId(),
                        ConflictResolution.Choice.THEIRS))
                .toList();
    }
}
