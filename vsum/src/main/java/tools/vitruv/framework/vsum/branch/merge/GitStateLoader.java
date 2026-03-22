package tools.vitruv.framework.vsum.branch.merge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import tools.vitruv.change.interaction.InteractionResultProvider;
import tools.vitruv.change.propagation.ChangePropagationSpecification;
import tools.vitruv.framework.vsum.VirtualModelBuilder;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;

/**
 * Utility to load VSUM state from a specific Git commit.
 * Used by the semantic merge engine to instantiate the target branch state.
 */
public class GitStateLoader {

    private static final Logger LOGGER = LogManager.getLogger(GitStateLoader.class);

    private final Path repoRoot;

    public GitStateLoader(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    /**
     * Checks out the VSUM files at a specific commit into a temporary directory.
     * Creates a fresh clone/worktree of the repo at the given commit.
     *
     * @param commitSha the commit to load
     * @param tempDir   the temporary directory to check out into
     * @throws IOException     if file operations fail
     * @throws GitAPIException if Git operations fail
     */
    public void checkoutStateAtCommit(String commitSha, Path tempDir) throws IOException, GitAPIException {
        LOGGER.info("Checking out state at commit {} into {}", commitSha.substring(0, 7), tempDir);

        // Use JGit to read the commit's tree and extract files to tempDir.
        // This avoids clone conflicts when the source repo has open file handles.
        try (Git git = Git.open(repoRoot.toFile())) {
            var repo = git.getRepository();
            var revCommit = new org.eclipse.jgit.revwalk.RevWalk(repo)
                    .parseCommit(repo.resolve(commitSha));
            var tree = revCommit.getTree();

            try (var treeWalk = new org.eclipse.jgit.treewalk.TreeWalk(repo)) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);
                while (treeWalk.next()) {
                    String path = treeWalk.getPathString();
                    Path outFile = tempDir.resolve(path);
                    Files.createDirectories(outFile.getParent());

                    var objectId = treeWalk.getObjectId(0);
                    var loader = repo.open(objectId);
                    Files.write(outFile, loader.getBytes());
                }
            }
        }
        LOGGER.debug("Extracted {} files at commit {}", countFiles(tempDir), commitSha.substring(0, 7));
    }

    private long countFiles(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }

    /**
     * Creates a fresh VirtualModel from a VSUM stored on disk.
     *
     * @param vsumFolder the folder containing the VSUM data (models, uuid, correspondences)
     * @param specs      the change propagation specifications to register
     * @return an initialized InternalVirtualModel
     */
    public static InternalVirtualModel loadVsumFromDir(
            Path vsumFolder,
            Collection<ChangePropagationSpecification> specs,
            InteractionResultProvider interactionProvider) throws IOException {
        VirtualModelBuilder builder = new VirtualModelBuilder()
                .withStorageFolder(vsumFolder)
                .withUserInteractorForResultProvider(interactionProvider);
        for (ChangePropagationSpecification spec : specs) {
            builder.withChangePropagationSpecifications(spec);
        }
        return builder.buildAndInitialize();
    }

    /**
     * Finds the merge base (common ancestor) of two commits using JGit.
     *
     * @param commit1 first commit SHA
     * @param commit2 second commit SHA
     * @return the merge base commit SHA, or null if none found
     */
    public String findMergeBase(String commit1, String commit2) throws IOException {
        try (Git git = Git.open(repoRoot.toFile());
             RevWalk walk = new RevWalk(git.getRepository())) {

            ObjectId id1 = git.getRepository().resolve(commit1);
            ObjectId id2 = git.getRepository().resolve(commit2);

            if (id1 == null || id2 == null) {
                throw new IOException("Cannot resolve commits: " + commit1 + " or " + commit2);
            }

            RevCommit rev1 = walk.parseCommit(id1);
            RevCommit rev2 = walk.parseCommit(id2);

            walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
            walk.markStart(rev1);
            walk.markStart(rev2);

            RevCommit mergeBase = walk.next();
            if (mergeBase == null) {
                LOGGER.warn("No merge base found between {} and {}", commit1, commit2);
                return null;
            }

            String baseSha = mergeBase.getName();
            LOGGER.info("Merge base between {} and {} is {}",
                    commit1.substring(0, 7), commit2.substring(0, 7), baseSha.substring(0, 7));
            return baseSha;
        }
    }

    /**
     * Creates a temporary directory for loading a commit's state.
     */
    public static Path createTempDir(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }
}
