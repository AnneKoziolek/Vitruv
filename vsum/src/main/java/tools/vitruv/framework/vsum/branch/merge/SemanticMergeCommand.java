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

    /**
     * Executes a bidirectional semantic merge between two branches.
     *
     * <p>Unlike {@link #execute}, which performs a directed merge (source→target),
     * this method tries both directions when indirect conflicts arise. If replaying
     * A onto B produces indirect conflicts (derived(A) vs user(B)), the reverse
     * direction B→A is attempted. If one direction is clean, that result is used.
     * If both directions have indirect conflicts, the merge is reported as a true conflict.
     *
     * @param repoRoot      the Git repository root
     * @param branchA       first branch name (symmetric — not source/target)
     * @param branchB       second branch name (symmetric — not source/target)
     * @param specs         the change propagation specifications
     * @param interactionProvider user interaction provider for VSUM creation
     * @param conflictResolutionProvider optional provider for resolving direct conflicts
     * @return the merge result, with {@link SemanticMergeResult.MergeDirection} indicating
     *         which direction was used
     */
    public SemanticMergeResult executeBidirectional(
            Path repoRoot,
            String branchA,
            String branchB,
            Collection<ChangePropagationSpecification> specs,
            InteractionResultProvider interactionProvider,
            ConflictResolutionProvider conflictResolutionProvider) throws Exception {

        LOGGER.info("Bidirectional semantic merge: {} <-> {}", branchA, branchB);

        String branchASha;
        String branchBSha;
        try (Git git = Git.open(repoRoot.toFile())) {
            Ref refA = git.getRepository().findRef(branchA);
            Ref refB = git.getRepository().findRef(branchB);

            if (refA == null) {
                throw new IOException("Cannot resolve branch: " + branchA);
            }
            if (refB == null) {
                throw new IOException("Cannot resolve branch: " + branchB);
            }

            branchASha = refA.getObjectId().getName();
            branchBSha = refB.getObjectId().getName();
        }

        GitStateLoader loader = new GitStateLoader(repoRoot);
        String baseSha = loader.findMergeBase(branchASha, branchBSha);
        if (baseSha == null) {
            throw new IOException("No common ancestor between " + branchA + " and " + branchB);
        }

        SemanticMergeEngine engine = new SemanticMergeEngine(
                repoRoot, specs, interactionProvider, conflictResolutionProvider);
        SemanticMergeResult result = engine.mergeBidirectional(baseSha, branchASha, branchBSha);

        LOGGER.info("Bidirectional merge result: {}", result);
        return result;
    }

    /**
     * Executes an interleaving semantic merge between two branches.
     *
     * <p>Instead of replaying all commits from one branch onto the other,
     * this method tries different interleavings of commits from both branches
     * starting from the common base. The first interleaving that produces no
     * indirect conflicts is used.
     *
     * <p>If no interleaving avoids indirect conflicts, returns a conflict result
     * with type {@link MergeConflict.ConflictType#INTERLEAVING_CONFLICT}.
     *
     * @param repoRoot      the Git repository root
     * @param branchA       first branch name
     * @param branchB       second branch name
     * @param specs         the change propagation specifications
     * @param interactionProvider user interaction provider for VSUM creation
     * @param conflictResolutionProvider optional provider for resolving direct conflicts
     * @return the merge result with direction INTERLEAVED if successful
     */
    public SemanticMergeResult executeWithInterleaving(
            Path repoRoot,
            String branchA,
            String branchB,
            Collection<ChangePropagationSpecification> specs,
            InteractionResultProvider interactionProvider,
            ConflictResolutionProvider conflictResolutionProvider) throws Exception {
        return executeWithInterleaving(repoRoot, branchA, branchB, specs,
                interactionProvider, conflictResolutionProvider,
                IntraBranchDependencyMode.CALCULATED);
    }

    public SemanticMergeResult executeWithInterleaving(
            Path repoRoot,
            String branchA,
            String branchB,
            Collection<ChangePropagationSpecification> specs,
            InteractionResultProvider interactionProvider,
            ConflictResolutionProvider conflictResolutionProvider,
            IntraBranchDependencyMode intraBranchMode) throws Exception {

        LOGGER.info("Interleaving semantic merge: {} <-> {} (intra-branch: {})",
                branchA, branchB, intraBranchMode);

        String branchASha;
        String branchBSha;
        try (Git git = Git.open(repoRoot.toFile())) {
            Ref refA = git.getRepository().findRef(branchA);
            Ref refB = git.getRepository().findRef(branchB);

            if (refA == null) {
                throw new IOException("Cannot resolve branch: " + branchA);
            }
            if (refB == null) {
                throw new IOException("Cannot resolve branch: " + branchB);
            }

            branchASha = refA.getObjectId().getName();
            branchBSha = refB.getObjectId().getName();
        }

        GitStateLoader loader = new GitStateLoader(repoRoot);
        String baseSha = loader.findMergeBase(branchASha, branchBSha);
        if (baseSha == null) {
            throw new IOException("No common ancestor between " + branchA + " and " + branchB);
        }

        SemanticMergeEngine engine = new SemanticMergeEngine(
                repoRoot, specs, interactionProvider, conflictResolutionProvider, intraBranchMode);
        SemanticMergeResult result = engine.mergeWithInterleaving(baseSha, branchASha, branchBSha);

        LOGGER.info("Interleaving merge result: {}", result);
        return result;
    }
}
