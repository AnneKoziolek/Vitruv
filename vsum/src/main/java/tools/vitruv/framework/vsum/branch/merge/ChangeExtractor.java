package tools.vitruv.framework.vsum.branch.merge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.hid.HierarchicalId;

/**
 * Extracts semantic changes for a commit range by reading persisted
 * {@link SemanticChangeLog} files from {@code .vitruvius/semantic-changelogs/}.
 *
 * <p>If no changelog files exist for a commit range, a state-based fallback
 * can be used via the merge engine (comparing model snapshots with EMFCompare).
 */
public class ChangeExtractor {

    private static final Logger LOGGER = LogManager.getLogger(ChangeExtractor.class);

    private final Path repoRoot;

    public ChangeExtractor(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    /**
     * Returns the ordered list of semantic change logs between two commits.
     * Walks from {@code tipSha} backwards to (but not including) {@code baseSha},
     * collecting changelogs for each commit that has one.
     *
     * @param baseSha the ancestor commit (exclusive)
     * @param tipSha  the tip commit (inclusive)
     * @return ordered list of change logs (oldest first), empty if none found
     */
    public List<SemanticChangeLog> getChangeLogsBetween(String baseSha, String tipSha) throws IOException {
        List<String> commitShas = getCommitsBetween(baseSha, tipSha);

        List<SemanticChangeLog> logs = new ArrayList<>();
        int missing = 0;
        for (String sha : commitShas) {
            if (SemanticChangeLog.existsFor(repoRoot, sha)) {
                SemanticChangeLog log = SemanticChangeLog.loadFrom(repoRoot, sha);
                if (log != null) {
                    logs.add(log);
                }
            } else {
                missing++;
            }
        }

        LOGGER.info("Found {} semantic changelogs for {} commits between {} and {} ({} missing)",
                logs.size(), commitShas.size(),
                baseSha.substring(0, 7), tipSha.substring(0, 7), missing);
        return logs;
    }

    /**
     * Extracts all primary EChanges from the changelogs in the given commit range.
     *
     * @return flat list of all primary EChanges (oldest first), empty if no changelogs found
     */
    public List<EChange<HierarchicalId>> getChangesBetween(String baseSha, String tipSha) throws IOException {
        List<SemanticChangeLog> logs = getChangeLogsBetween(baseSha, tipSha);
        List<EChange<HierarchicalId>> allChanges = new ArrayList<>();
        for (SemanticChangeLog log : logs) {
            allChanges.addAll(log.getPrimaryChanges());
        }
        return allChanges;
    }

    /**
     * Returns true if semantic changelogs exist for all commits in the range.
     */
    public boolean hasCompleteChangeLogs(String baseSha, String tipSha) throws IOException {
        List<String> commits = getCommitsBetween(baseSha, tipSha);
        return commits.stream().allMatch(sha -> SemanticChangeLog.existsFor(repoRoot, sha));
    }

    /**
     * Enumerates commit SHAs from tipSha back to baseSha (exclusive), oldest first.
     */
    private List<String> getCommitsBetween(String baseSha, String tipSha) throws IOException {
        List<String> shas = new ArrayList<>();
        try (Git git = Git.open(repoRoot.toFile());
             RevWalk walk = new RevWalk(git.getRepository())) {

            ObjectId tipId = git.getRepository().resolve(tipSha);
            ObjectId baseId = git.getRepository().resolve(baseSha);

            if (tipId == null || baseId == null) {
                throw new IOException("Cannot resolve commits: " + baseSha + " or " + tipSha);
            }

            walk.markStart(walk.parseCommit(tipId));
            walk.markUninteresting(walk.parseCommit(baseId));

            for (RevCommit commit : walk) {
                shas.add(commit.getName());
            }
        }

        // RevWalk returns newest first; reverse to get oldest first
        Collections.reverse(shas);
        return shas;
    }
}
