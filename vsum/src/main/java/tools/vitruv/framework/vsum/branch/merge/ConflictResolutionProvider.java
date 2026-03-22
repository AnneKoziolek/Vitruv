package tools.vitruv.framework.vsum.branch.merge;

import java.util.List;

/**
 * Strategy for resolving merge conflicts.
 * Receives a list of conflicts and returns resolutions (ours or theirs per conflict).
 */
@FunctionalInterface
public interface ConflictResolutionProvider {

    /**
     * Resolves the given conflicts by choosing OURS or THEIRS for each.
     *
     * @param conflicts the detected merge conflicts
     * @return one resolution per conflict
     */
    List<ConflictResolution> resolve(List<MergeConflict> conflicts);

    /**
     * Returns a provider that always chooses OURS (keep target branch values).
     */
    static ConflictResolutionProvider chooseAllOurs() {
        return conflicts -> conflicts.stream()
                .map(c -> new ConflictResolution(c.getElementId(),
                        ConflictResolution.Choice.OURS))
                .toList();
    }

    /**
     * Returns a provider that always chooses THEIRS (accept source branch values).
     */
    static ConflictResolutionProvider chooseAllTheirs() {
        return conflicts -> conflicts.stream()
                .map(c -> new ConflictResolution(c.getElementId(),
                        ConflictResolution.Choice.THEIRS))
                .toList();
    }
}
