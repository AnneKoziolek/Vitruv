package tools.vitruv.framework.vsum.branch.merge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.eobject.EObjectExistenceEChange;
import tools.vitruv.change.atomic.feature.FeatureEChange;
import tools.vitruv.change.atomic.feature.attribute.InsertEAttributeValue;
import tools.vitruv.change.atomic.feature.attribute.RemoveEAttributeValue;
import tools.vitruv.change.atomic.feature.attribute.ReplaceSingleValuedEAttribute;
import tools.vitruv.change.atomic.feature.reference.InsertEReference;
import tools.vitruv.change.atomic.feature.reference.RemoveEReference;
import tools.vitruv.change.atomic.feature.reference.ReplaceSingleValuedEReference;
import tools.vitruv.change.atomic.hid.HierarchicalId;
import tools.vitruv.change.atomic.root.InsertRootEObject;
import tools.vitruv.change.atomic.root.RemoveRootEObject;
import tools.vitruv.change.atomic.root.RootEChange;

/**
 * A semantic change log that stores the primary {@link EChange}s for a single Git commit.
 *
 * <p>Changes are serialized as JSON DTOs capturing change type, affected element IDs (both
 * HierarchicalId and UUID), feature names, and values. The DTOs are used both for UUID-based
 * conflict detection and for reconstructing live EChange objects via {@link ChangeDtoDeserializer}.
 *
 * <p>In addition to per-change data, the changelog optionally stores
 * <b>cascade-deleted child UUIDs</b> (via {@link ChangeDto#cascadeDeletedUuids}).
 * When a parent element is removed from a containment reference, EMF implicitly deletes
 * all contained children. Their UUIDs are captured by {@link ChangeLogCapture} at commit
 * time and attached to the parent's removal DTO during serialization in {@link #saveTo(Path)}.
 * This enables {@link UuidConflictDetector} to statically detect conflicts on
 * cascade-deleted children whose UUIDs would otherwise not appear in the changelog.
 *
 * <p>Note: XMI serialization was attempted but fails because the EChange Ecore metamodel
 * defines element references via generic {@code ETypeParameter} with bounds {@code EJavaObject},
 * preventing proper cross-reference serialization of HierarchicalId objects.
 *
 * <p>Storage location: {@code .vitruvius/semantic-changelogs/<key>.changelog.json}
 */
public class SemanticChangeLog {

    private static final Logger LOGGER = LogManager.getLogger(SemanticChangeLog.class);
    private static final String CHANGELOG_DIR = ".vitruvius/semantic-changelogs";
    private static final String JSON_EXTENSION = ".changelog.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String commitSha;
    private final String branch;
    private final List<EChange<HierarchicalId>> primaryChanges;
    private final Map<String, String> uuidMappings; // uuid → hierarchicalId
    private final Map<String, List<String>> cascadeDeletedUuids; // parentUuid → [childUuids]
    private final Set<String> consequentialFootprints; // UUID#feature pairs from Reactions
    private final boolean hasConsequentialFootprints; // true when footprints were explicitly provided

    public SemanticChangeLog(String commitSha, String branch,
                             List<EChange<HierarchicalId>> primaryChanges) {
        this(commitSha, branch, primaryChanges, Map.of(), Map.of(), null);
    }

    public SemanticChangeLog(String commitSha, String branch,
                             List<EChange<HierarchicalId>> primaryChanges,
                             Map<String, String> uuidMappings) {
        this(commitSha, branch, primaryChanges, uuidMappings, Map.of(), null);
    }

    public SemanticChangeLog(String commitSha, String branch,
                             List<EChange<HierarchicalId>> primaryChanges,
                             Map<String, String> uuidMappings,
                             Map<String, List<String>> cascadeDeletedUuids) {
        this(commitSha, branch, primaryChanges, uuidMappings, cascadeDeletedUuids, null);
    }

    /**
     * Full constructor. Pass a non-null {@code consequentialFootprints} set (even if empty)
     * to indicate that footprints were captured during commit. Pass {@code null} to indicate
     * that footprints are not available (old-format changelog).
     */
    public SemanticChangeLog(String commitSha, String branch,
                             List<EChange<HierarchicalId>> primaryChanges,
                             Map<String, String> uuidMappings,
                             Map<String, List<String>> cascadeDeletedUuids,
                             Set<String> consequentialFootprints) {
        this.commitSha = Objects.requireNonNull(commitSha, "commitSha must not be null");
        this.branch = Objects.requireNonNull(branch, "branch must not be null");
        this.primaryChanges = Collections.unmodifiableList(new ArrayList<>(primaryChanges));
        this.uuidMappings = Map.copyOf(uuidMappings);
        this.cascadeDeletedUuids = cascadeDeletedUuids != null
                ? Map.copyOf(cascadeDeletedUuids) : Map.of();
        this.hasConsequentialFootprints = consequentialFootprints != null;
        this.consequentialFootprints = consequentialFootprints != null
                ? Set.copyOf(consequentialFootprints) : Set.of();
    }

    public String getCommitSha() { return commitSha; }
    public String getBranch() { return branch; }
    public List<EChange<HierarchicalId>> getPrimaryChanges() { return primaryChanges; }
    public Map<String, String> getUuidMappings() { return uuidMappings; }
    public Set<String> getConsequentialFootprints() { return consequentialFootprints; }

    /**
     * Persists this change log as JSON DTOs.
     *
     * <p>Note: XMI serialization of EChange<HierarchicalId> was attempted but fails
     * because the generic Element type parameter is stored as EJavaObject (not EReference)
     * in the EMF metamodel, so HierarchicalId objects can't be serialized as cross-references.
     * The JSON DTO format captures all essential information for both conflict detection
     * and EChange reconstruction.
     */
    public void saveTo(Path repoRoot) throws IOException {
        Path changelogDir = repoRoot.resolve(CHANGELOG_DIR);
        Files.createDirectories(changelogDir);
        String shortSha = commitSha.substring(0, Math.min(7, commitSha.length()));

        // Determine chronological commit index (count existing changelog files)
        int index = 0;
        if (Files.exists(changelogDir)) {
            try (var stream = Files.list(changelogDir)) {
                index = (int) stream.filter(f -> f.toString().endsWith(JSON_EXTENSION)).count();
            }
        }

        // Save JSON DTOs
        Path jsonPath = changelogDir.resolve(shortSha + JSON_EXTENSION);
        saveToJson(jsonPath, index);

        // Save metadata
        Path metaPath = changelogDir.resolve(shortSha + ".meta");
        Files.writeString(metaPath, "commitSha=%s\nbranch=%s\nchangeCount=%d\n"
                .formatted(commitSha, branch, primaryChanges.size()));

        LOGGER.info("Saved semantic changelog for {} ({} changes) to {}",
                shortSha, primaryChanges.size(), jsonPath);
    }

    /**
     * Loads a semantic change log metadata from disk.
     * Returns the changelog with empty primaryChanges (use loadDtosFrom for data).
     */
    public static SemanticChangeLog loadFrom(Path repoRoot, String commitSha) throws IOException {
        String shortSha = commitSha.substring(0, Math.min(7, commitSha.length()));
        Path jsonPath = repoRoot.resolve(CHANGELOG_DIR).resolve(shortSha + JSON_EXTENSION);

        if (!Files.exists(jsonPath)) return null;

        ChangeLogDto dto = GSON.fromJson(Files.readString(jsonPath), ChangeLogDto.class);
        return new SemanticChangeLog(dto.commitSha, dto.branch, List.of());
    }

    /**
     * Loads JSON DTOs from the companion changelog (for conflict detection).
     */
    public static List<ChangeDto> loadDtosFrom(Path repoRoot, String commitSha) throws IOException {
        String shortSha = commitSha.substring(0, Math.min(7, commitSha.length()));
        Path jsonPath = repoRoot.resolve(CHANGELOG_DIR).resolve(shortSha + JSON_EXTENSION);
        if (!Files.exists(jsonPath)) return List.of();

        ChangeLogDto dto = GSON.fromJson(Files.readString(jsonPath), ChangeLogDto.class);
        return dto.changes != null ? dto.changes : List.of();
    }

    /**
     * Loads UUID-to-HierarchicalId mappings from a changelog JSON file.
     * Used for UUID-based element resolution during interleaving replay.
     */
    public static Map<String, String> loadUuidMappingsFrom(Path repoRoot, String commitSha)
            throws IOException {
        String shortSha = commitSha.substring(0, Math.min(7, commitSha.length()));
        Path jsonPath = repoRoot.resolve(CHANGELOG_DIR).resolve(shortSha + JSON_EXTENSION);
        if (!Files.exists(jsonPath)) return Map.of();

        ChangeLogDto dto = GSON.fromJson(Files.readString(jsonPath), ChangeLogDto.class);
        return dto.uuidMappings != null ? dto.uuidMappings : Map.of();
    }

    /**
     * Loads consequential footprints from a changelog JSON file.
     *
     * @return {@code null} if the field is absent (old-format changelog),
     *         or a (possibly empty) set of UUID#feature strings if present
     */
    public static Set<String> loadConsequentialFootprintsFrom(Path repoRoot, String commitSha)
            throws IOException {
        String shortSha = commitSha.substring(0, Math.min(7, commitSha.length()));
        Path jsonPath = repoRoot.resolve(CHANGELOG_DIR).resolve(shortSha + JSON_EXTENSION);
        if (!Files.exists(jsonPath)) return null;

        ChangeLogDto dto = GSON.fromJson(Files.readString(jsonPath), ChangeLogDto.class);
        if (dto.consequentialFootprints == null) return null; // field absent → old format
        return new HashSet<>(dto.consequentialFootprints);
    }

    /**
     * Loads the commit index from a changelog JSON file.
     * Returns -1 if the field is absent (old-format changelog).
     */
    public static int loadCommitIndexFrom(Path repoRoot, String commitSha) throws IOException {
        String shortSha = commitSha.substring(0, Math.min(7, commitSha.length()));
        Path jsonPath = repoRoot.resolve(CHANGELOG_DIR).resolve(shortSha + JSON_EXTENSION);
        if (!Files.exists(jsonPath)) return -1;

        ChangeLogDto dto = GSON.fromJson(Files.readString(jsonPath), ChangeLogDto.class);
        return dto.commitIndex;
    }

    public static boolean existsFor(Path repoRoot, String commitSha) {
        String shortSha = commitSha.substring(0, Math.min(7, commitSha.length()));
        return Files.exists(repoRoot.resolve(CHANGELOG_DIR).resolve(shortSha + JSON_EXTENSION));
    }

    public static Path getChangelogDirectory(Path repoRoot) {
        return repoRoot.resolve(CHANGELOG_DIR);
    }

    // === JSON DTO Serialization ===

    private void saveToJson(Path jsonPath, int commitIndex) throws IOException {
        List<ChangeDto> dtos = new ArrayList<>();
        for (EChange<HierarchicalId> change : primaryChanges) {
            ChangeDto dto = ChangeDto.fromEChange(change);
            // Enrich with UUID from mapping if available
            if (dto.affectedElementId != null && uuidMappings.containsValue(dto.affectedElementId)) {
                for (var entry : uuidMappings.entrySet()) {
                    if (entry.getValue().equals(dto.affectedElementId)) {
                        dto.affectedElementUuid = entry.getKey();
                        break;
                    }
                }
            }
            // Enrich with cascade-deleted UUIDs if this DTO's UUID has cascade children
            if (dto.affectedElementUuid != null
                    && cascadeDeletedUuids.containsKey(dto.affectedElementUuid)) {
                dto.cascadeDeletedUuids = cascadeDeletedUuids.get(dto.affectedElementUuid);
            }
            dtos.add(dto);
        }
        ChangeLogDto logDto = new ChangeLogDto();
        logDto.commitSha = commitSha;
        logDto.branch = branch;
        logDto.commitIndex = commitIndex;
        logDto.changes = dtos;
        logDto.uuidMappings = uuidMappings.isEmpty() ? null : new HashMap<>(uuidMappings);
        logDto.consequentialFootprints = hasConsequentialFootprints
                ? new ArrayList<>(consequentialFootprints) : null;
        Files.writeString(jsonPath, GSON.toJson(logDto));
    }

    @Override
    public String toString() {
        return "SemanticChangeLog{commit=%s, branch=%s, changes=%d}"
                .formatted(commitSha.substring(0, Math.min(7, commitSha.length())),
                        branch, primaryChanges.size());
    }

    // === DTOs ===

    static class ChangeLogDto {
        String commitSha;
        String branch;
        int commitIndex = -1; // chronological order on branch (0-based)
        List<ChangeDto> changes;
        Map<String, String> uuidMappings; // uuid → hierarchicalId
        List<String> consequentialFootprints; // UUID#feature pairs from Reactions
    }

    public static class ChangeDto {
        public String changeType;
        public String affectedElementId;
        public String affectedElementUuid; // UUID for cross-branch identity
        public String affectedEClassName;  // EClass name for feature resolution
        public String featureName;
        public String oldValueId;
        public String newValueId;
        public Object oldLiteralValue;
        public Object newLiteralValue;
        public int index = -1;
        public String resourceUri;
        public String affectedEObjectType;
        /**
         * UUIDs of all contained children that are implicitly cascade-deleted when
         * this element is removed from its containment reference. Populated at changelog
         * capture time by walking {@code eAllContents()} of the removed element.
         * Used by {@link UuidConflictDetector} to detect conflicts on child elements.
         * Null if this change is not a removal or the element has no children.
         */
        public List<String> cascadeDeletedUuids;

        @SuppressWarnings("unchecked")
        public static ChangeDto fromEChange(EChange<HierarchicalId> change) {
            ChangeDto dto = new ChangeDto();
            dto.changeType = change.getClass().getSimpleName().replaceAll("Impl$", "");

            if (change instanceof FeatureEChange<HierarchicalId, ?> fc) {
                dto.affectedElementId = idToString(fc.getAffectedElement());
                dto.featureName = fc.getAffectedFeature() != null
                        ? fc.getAffectedFeature().getName() : null;
                // Capture EClass name for feature resolution during deserialization
                if (fc.getAffectedFeature() != null && fc.getAffectedFeature().getEContainingClass() != null) {
                    dto.affectedEClassName = fc.getAffectedFeature().getEContainingClass().getName();
                }
            }
            if (change instanceof EObjectExistenceEChange<HierarchicalId> ec) {
                dto.affectedElementId = idToString(ec.getAffectedElement());
                dto.affectedEObjectType = ec.getAffectedEObjectType() != null
                        ? ec.getAffectedEObjectType().getName() : null;
            }
            if (change instanceof InsertEAttributeValue<HierarchicalId, ?> ia) {
                dto.newLiteralValue = ia.getNewValue();
                dto.index = ia.getIndex();
            }
            if (change instanceof RemoveEAttributeValue<HierarchicalId, ?> ra) {
                dto.oldLiteralValue = ra.getOldValue();
                dto.index = ra.getIndex();
            }
            if (change instanceof ReplaceSingleValuedEAttribute<HierarchicalId, ?> rsa) {
                dto.oldLiteralValue = rsa.getOldValue();
                dto.newLiteralValue = rsa.getNewValue();
            }
            if (change instanceof InsertEReference<HierarchicalId> ir) {
                dto.newValueId = idToString(ir.getNewValue());
                dto.index = ir.getIndex();
            }
            if (change instanceof RemoveEReference<HierarchicalId> rr) {
                dto.oldValueId = idToString(rr.getOldValue());
                dto.index = rr.getIndex();
            }
            if (change instanceof ReplaceSingleValuedEReference<HierarchicalId> rsr) {
                dto.oldValueId = idToString(rsr.getOldValue());
                dto.newValueId = idToString(rsr.getNewValue());
            }
            if (change instanceof RootEChange<HierarchicalId> rc) {
                dto.resourceUri = rc.getUri();
                dto.index = rc.getIndex();
            }
            if (change instanceof InsertRootEObject<HierarchicalId> iro) {
                dto.newValueId = idToString(iro.getNewValue());
            }
            if (change instanceof RemoveRootEObject<HierarchicalId> rro) {
                dto.oldValueId = idToString(rro.getOldValue());
            }
            return dto;
        }

        private static String idToString(HierarchicalId id) {
            // Use getId() not toString() to avoid the "Id(...)" wrapper
            return id != null ? id.getId() : null;
        }

        @Override
        public String toString() {
            return changeType + "{element=" + affectedElementId
                    + (featureName != null ? ", feature=" + featureName : "")
                    + "}";
        }
    }
}
