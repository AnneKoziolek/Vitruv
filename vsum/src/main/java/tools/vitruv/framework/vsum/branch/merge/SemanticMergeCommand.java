package tools.vitruv.framework.vsum.branch.merge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;

import tools.vitruv.change.interaction.InteractionResultProvider;
import tools.vitruv.change.propagation.ChangePropagationSpecification;

/**
 * Entry point for performing a semantic three-way merge.
 * Can be invoked programmatically from tests or from a Git merge driver.
 */
public class SemanticMergeCommand {

    private static final Logger LOGGER = LogManager.getLogger(SemanticMergeCommand.class);

    /**
     * Executes a semantic three-way merge between two branches.
     *
     * @param repoRoot      the Git repository root
     * @param sourceBranch  the branch to merge from ("theirs")
     * @param targetBranch  the branch to merge into ("ours")
     * @param specs         the change propagation specifications
     * @param interactionProvider user interaction provider for VSUM creation
     * @return the merge result
     */
    /** Executes without conflict resolution (aborts on conflict). */
    public SemanticMergeResult execute(
            Path repoRoot,
            String sourceBranch,
            String targetBranch,
            Collection<ChangePropagationSpecification> specs,
            InteractionResultProvider interactionProvider) throws Exception {
        return execute(repoRoot, sourceBranch, targetBranch, specs, interactionProvider, null);
    }

    /** Executes with optional conflict resolution provider. */
    public SemanticMergeResult execute(
            Path repoRoot,
            String sourceBranch,
            String targetBranch,
            Collection<ChangePropagationSpecification> specs,
            InteractionResultProvider interactionProvider,
            ConflictResolutionProvider conflictResolutionProvider) throws Exception {

        LOGGER.info("Semantic merge: {} -> {}", sourceBranch, targetBranch);

        String oursSha;
        String theirsSha;
        try (Git git = Git.open(repoRoot.toFile())) {
            Ref oursRef = git.getRepository().findRef(targetBranch);
            Ref theirsRef = git.getRepository().findRef(sourceBranch);

            if (oursRef == null) {
                throw new IOException("Cannot resolve target branch: " + targetBranch);
            }
            if (theirsRef == null) {
                throw new IOException("Cannot resolve source branch: " + sourceBranch);
            }

            oursSha = oursRef.getObjectId().getName();
            theirsSha = theirsRef.getObjectId().getName();
        }

        GitStateLoader loader = new GitStateLoader(repoRoot);
        String baseSha = loader.findMergeBase(oursSha, theirsSha);
        if (baseSha == null) {
            throw new IOException("No common ancestor between " + targetBranch + " and " + sourceBranch);
        }

        SemanticMergeEngine engine = new SemanticMergeEngine(
                repoRoot, specs, interactionProvider, conflictResolutionProvider);
        SemanticMergeResult result = engine.merge(baseSha, oursSha, theirsSha);

        LOGGER.info("Semantic merge result: {}", result);
        return result;
    }
}
