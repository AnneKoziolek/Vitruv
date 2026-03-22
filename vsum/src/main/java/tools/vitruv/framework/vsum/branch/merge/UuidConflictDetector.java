package tools.vitruv.framework.vsum.branch.merge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import tools.vitruv.framework.vsum.branch.merge.SemanticChangeLog.ChangeDto;

/**
 * Detects semantic conflicts between two branches' changelogs using UUID-based
 * element identity. This avoids the false positives of position-based matching
 * (EMFCompare) because UUIDs are stable across branches for elements that
 * existed in the common ancestor.
 *
 * <p>Conflict rules:
 * <ul>
 *   <li>Both branches modify the same attribute of the same element (by UUID) → MODIFY_MODIFY</li>
 *   <li>One branch deletes an element, the other modifies it → DELETE_MODIFY / MODIFY_DELETE</li>
 *   <li>Both branches add new elements (different UUIDs) → NOT a conflict</li>
 *   <li>Both branches insert into the same list → NOT a conflict (additive)</li>
 * </ul>
 */
public class UuidConflictDetector {

    private static final Logger LOGGER = LogManager.getLogger(UuidConflictDetector.class);

    /**
     * Detects conflicts by comparing two sets of changelog DTOs.
     *
     * @param oursDtos   changes from the target branch (ours)
     * @param theirsDtos changes from the source branch (theirs)
     * @return list of conflicts (empty if none)
     */
    public List<MergeConflict> detectConflicts(List<ChangeDto> oursDtos, List<ChangeDto> theirsDtos) {
        // Group modifications by UUID+feature (only ReplaceSingleValued changes can conflict)
        Map<String, ChangeDto> oursModifications = extractModifications(oursDtos);
        Map<String, ChangeDto> theirsModifications = extractModifications(theirsDtos);

        // Collect deleted element UUIDs
        Set<String> oursDeleted = extractDeletedUuids(oursDtos);
        Set<String> theirsDeleted = extractDeletedUuids(theirsDtos);

        // Collect modified element UUIDs (any change type)
        Set<String> oursModifiedUuids = extractModifiedUuids(oursDtos);
        Set<String> theirsModifiedUuids = extractModifiedUuids(theirsDtos);

        List<MergeConflict> conflicts = new ArrayList<>();

        // 1. MODIFY_MODIFY: same UUID+feature changed by both branches
        Set<String> overlapping = new HashSet<>(oursModifications.keySet());
        overlapping.retainAll(theirsModifications.keySet());

        for (String key : overlapping) {
            ChangeDto ours = oursModifications.get(key);
            ChangeDto theirs = theirsModifications.get(key);

            // Check if they set different values
            if (!Objects.equals(ours.newLiteralValue, theirs.newLiteralValue)) {
                String uuid = ours.affectedElementUuid;
                conflicts.add(new MergeConflict(
                        ours.affectedElementId,
                        MergeConflict.ConflictType.MODIFY_MODIFY,
                        uuid, ours.featureName,
                        String.valueOf(ours.oldLiteralValue),
                        String.valueOf(ours.newLiteralValue),
                        String.valueOf(theirs.newLiteralValue)
                ));
            }
            // If same value → both made identical change, no conflict
        }

        // 2. DELETE_MODIFY: ours deleted, theirs modified
        for (String deletedUuid : oursDeleted) {
            if (theirsModifiedUuids.contains(deletedUuid)) {
                conflicts.add(new MergeConflict(
                        deletedUuid,
                        MergeConflict.ConflictType.DELETE_MODIFY,
                        deletedUuid, null, null, null, null
                ));
            }
        }

        // 3. MODIFY_DELETE: theirs deleted, ours modified
        for (String deletedUuid : theirsDeleted) {
            if (oursModifiedUuids.contains(deletedUuid)) {
                conflicts.add(new MergeConflict(
                        deletedUuid,
                        MergeConflict.ConflictType.MODIFY_DELETE,
                        deletedUuid, null, null, null, null
                ));
            }
        }

        if (conflicts.isEmpty()) {
            LOGGER.debug("No UUID-based conflicts detected");
        } else {
            LOGGER.info("Detected {} UUID-based conflicts", conflicts.size());
        }

        return conflicts;
    }

    /**
     * Extracts single-valued attribute/reference modifications keyed by UUID+feature.
     * Only ReplaceSingleValued changes can cause real MODIFY_MODIFY conflicts.
     */
    private Map<String, ChangeDto> extractModifications(List<ChangeDto> dtos) {
        Map<String, ChangeDto> mods = new HashMap<>();
        for (ChangeDto dto : dtos) {
            if (dto.affectedElementUuid == null) continue;
            if (dto.changeType.startsWith("ReplaceSingleValued") && dto.featureName != null) {
                String key = dto.affectedElementUuid + "#" + dto.featureName;
                mods.put(key, dto);
            }
        }
        return mods;
    }

    /**
     * Extracts UUIDs of elements deleted by this branch's changes.
     */
    private Set<String> extractDeletedUuids(List<ChangeDto> dtos) {
        Set<String> deleted = new HashSet<>();
        for (ChangeDto dto : dtos) {
            if (dto.affectedElementUuid != null && "DeleteEObject".equals(dto.changeType)) {
                deleted.add(dto.affectedElementUuid);
            }
        }
        return deleted;
    }

    /**
     * Extracts UUIDs of all elements modified by this branch (any change type except Create).
     */
    private Set<String> extractModifiedUuids(List<ChangeDto> dtos) {
        Set<String> modified = new HashSet<>();
        for (ChangeDto dto : dtos) {
            if (dto.affectedElementUuid != null && !"CreateEObject".equals(dto.changeType)) {
                modified.add(dto.affectedElementUuid);
            }
        }
        return modified;
    }
}
