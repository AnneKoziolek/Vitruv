package tools.vitruv.framework.vsum.branch.merge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.eobject.DeleteEObject;
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
 * Changes are serialized as JSON using a lightweight DTO representation.
 *
 * <p>Storage location: {@code .vitruvius/semantic-changelogs/<commitSha>.changelog}
 */
public class SemanticChangeLog {

    private static final Logger LOGGER = LogManager.getLogger(SemanticChangeLog.class);
    private static final String CHANGELOG_DIR = ".vitruvius/semantic-changelogs";
    private static final String CHANGELOG_EXTENSION = ".changelog";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String commitSha;
    private final String branch;
    private final List<EChange<HierarchicalId>> primaryChanges;

    public SemanticChangeLog(String commitSha, String branch,
                             List<EChange<HierarchicalId>> primaryChanges) {
        this.commitSha = Objects.requireNonNull(commitSha, "commitSha must not be null");
        this.branch = Objects.requireNonNull(branch, "branch must not be null");
        this.primaryChanges = Collections.unmodifiableList(new ArrayList<>(primaryChanges));
    }

    public String getCommitSha() { return commitSha; }
    public String getBranch() { return branch; }
    public List<EChange<HierarchicalId>> getPrimaryChanges() { return primaryChanges; }

    /**
     * Persists this change log as JSON.
     */
    public void saveTo(Path repoRoot) throws IOException {
        Path changelogDir = repoRoot.resolve(CHANGELOG_DIR);
        Files.createDirectories(changelogDir);
        String shortSha = commitSha.substring(0, Math.min(7, commitSha.length()));

        // Convert EChanges to serializable DTOs
        List<ChangeDto> dtos = new ArrayList<>();
        for (EChange<HierarchicalId> change : primaryChanges) {
            dtos.add(ChangeDto.fromEChange(change));
        }

        ChangeLogDto logDto = new ChangeLogDto();
        logDto.commitSha = commitSha;
        logDto.branch = branch;
        logDto.changes = dtos;

        Path path = changelogDir.resolve(shortSha + CHANGELOG_EXTENSION);
        Files.writeString(path, GSON.toJson(logDto));

        LOGGER.info("Saved semantic changelog for {} ({} changes) to {}",
                shortSha, primaryChanges.size(), path);
    }

    /**
     * Loads a semantic change log from disk. Returns null if not found.
     * Note: loaded changes are returned as DTOs wrapped in a placeholder list.
     * The merge engine uses {@link #loadDtosFrom} for replay.
     */
    public static SemanticChangeLog loadFrom(Path repoRoot, String commitSha) throws IOException {
        String shortSha = commitSha.substring(0, Math.min(7, commitSha.length()));
        Path path = repoRoot.resolve(CHANGELOG_DIR).resolve(shortSha + CHANGELOG_EXTENSION);
        if (!Files.exists(path)) return null;

        ChangeLogDto dto = GSON.fromJson(Files.readString(path), ChangeLogDto.class);
        // Return with empty primaryChanges — use loadDtosFrom for actual data
        return new SemanticChangeLog(dto.commitSha, dto.branch, List.of());
    }

    /**
     * Loads change DTOs from a persisted changelog.
     */
    public static List<ChangeDto> loadDtosFrom(Path repoRoot, String commitSha) throws IOException {
        String shortSha = commitSha.substring(0, Math.min(7, commitSha.length()));
        Path path = repoRoot.resolve(CHANGELOG_DIR).resolve(shortSha + CHANGELOG_EXTENSION);
        if (!Files.exists(path)) return List.of();

        ChangeLogDto dto = GSON.fromJson(Files.readString(path), ChangeLogDto.class);
        return dto.changes != null ? dto.changes : List.of();
    }

    public static boolean existsFor(Path repoRoot, String commitSha) {
        String shortSha = commitSha.substring(0, Math.min(7, commitSha.length()));
        return Files.exists(repoRoot.resolve(CHANGELOG_DIR).resolve(shortSha + CHANGELOG_EXTENSION));
    }

    public static Path getChangelogDirectory(Path repoRoot) {
        return repoRoot.resolve(CHANGELOG_DIR);
    }

    @Override
    public String toString() {
        return "SemanticChangeLog{commit=%s, branch=%s, changes=%d}"
                .formatted(commitSha.substring(0, Math.min(7, commitSha.length())),
                        branch, primaryChanges.size());
    }

    // === Serializable DTOs ===

    /** Top-level changelog DTO. */
    static class ChangeLogDto {
        String commitSha;
        String branch;
        List<ChangeDto> changes;
    }

    /** Lightweight DTO capturing the essential information from an EChange. */
    public static class ChangeDto {
        public String changeType;
        public String affectedElementId;
        public String featureName;
        public String oldValueId;
        public String newValueId;
        public Object oldLiteralValue;
        public Object newLiteralValue;
        public int index = -1;
        public String resourceUri;
        public String affectedEObjectType;

        @SuppressWarnings("unchecked")
        public static ChangeDto fromEChange(EChange<HierarchicalId> change) {
            ChangeDto dto = new ChangeDto();
            dto.changeType = change.getClass().getSimpleName().replaceAll("Impl$", "");

            if (change instanceof FeatureEChange<HierarchicalId, ?> fc) {
                dto.affectedElementId = idToString(fc.getAffectedElement());
                dto.featureName = fc.getAffectedFeature() != null
                        ? fc.getAffectedFeature().getName() : null;
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
            return id != null ? id.toString() : null;
        }

        @Override
        public String toString() {
            return changeType + "{element=" + affectedElementId
                    + (featureName != null ? ", feature=" + featureName : "")
                    + "}";
        }
    }
}
