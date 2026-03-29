# Vitruv
[![GitHub Action CI](https://github.com/vitruv-tools/Vitruv/actions/workflows/ci.yml/badge.svg)](https://github.com/vitruv-tools/Vitruv/actions/workflows/ci.yml)
[![Latest Release](https://img.shields.io/github/release/vitruv-tools/Vitruv.svg)](https://github.com/vitruv-tools/Vitruv/releases/latest)
[![Issues](https://img.shields.io/github/issues/vitruv-tools/Vitruv.svg)](https://github.com/vitruv-tools/Vitruv/issues)
[![License](https://img.shields.io/github/license/vitruv-tools/Vitruv.svg)](https://raw.githubusercontent.com/vitruv-tools/Vitruv/main/LICENSE)

[Vitruvius](https://vitruv.tools) is a framework for view-based (software) development.
It assumes different models to be used for describing a system, which are automatically kept consistent by the framework executing (semi-)automated rules that preserve consistency.
These models are modified only via views, which are projections from the underlying models.
For general information on Vitruvius, see our [GitHub Organisation](https://github.com/vitruv-tools) and our [Wiki](https://github.com/vitruv-tools/.github/wiki).

This project contains the central Vitruvius framework, providing the definition of a V-SUM (Virtual Single Underlying Model) containing development artifacts to be kept consistent and to be accessed and modified via views.
In the implementation, a V-SUM is called `VirtualModel`, which is instantiated with a set of `ChangePropagationSpecifications` (no matter whether they are developed with the [Vitruv-DSLs](https://github.com/vitruv-tools/Vitruv-DSLs) or just as an implementation of the interface defined in the [Vitruv-Change](https://github.com/vitruv-tools/Vitruv-Change) repository).
The `VirtualModel` then provides functionality to derive and modify views and to propagate the changes in these views back to the `VirtualModel`, which then executes the `ChangePropagationSpecifications` to preserve consistency.

## Branching and Semantic Merge

The `vsum` module includes experimental support for **Git-integrated branching and semantic three-way merge** of multi-model VSUMs. This extends Vitruvius with:

- **Git hooks** (`post-checkout`, `pre-commit`, `post-commit`) that keep the in-memory VSUM synchronized with the Git working tree.
- **Semantic changelog capture** that records original (user-authored) changes per commit as JSON DTOs with UUID-based element identity.
- **Replay-based merge engine** (`SemanticMergeEngine`) that replays source-branch transactions on the target branch through the standard change propagation pipeline, letting Reactions regenerate consequential changes.
- **Provenance-aware conflict classification**: direct conflicts (original vs. original), consequential overlaps, and indirect conflicts (consequential vs. original).
- **Interleaving merge** that finds a conflict-free ordering of transactions from both branches.
- **Git custom merge driver** (`GitMergeDriver`) that integrates the semantic merge into `git merge` via `.gitattributes`.

### Key Classes (in `tools.vitruv.framework.vsum.branch.merge`)

| Class | Purpose |
|-------|---------|
| `SemanticMergeEngine` | Core merge algorithm: directed, bidirectional, and interleaving modes |
| `SemanticMergeCommand` | Entry point taking branch names, resolving SHAs, delegating to engine |
| `GitMergeDriver` | Git custom merge driver (`main()` class invoked per-file by Git) |
| `ChangeLogCapture` | `ChangePropagationListener` that records original EChanges per commit |
| `SemanticChangeLog` | Persists changelog DTOs as JSON in `.vitruvius/semantic-changelogs/` |
| `UuidConflictDetector` | UUID-based static conflict detection on changelog DTOs |
| `ChangeDtoDeserializer` | Reconstructs `EChange<HierarchicalId>` from JSON DTOs for replay |
| `GitStateLoader` | Extracts VSUM state from any Git commit via JGit TreeWalk |

### Git Merge Driver

The `GitMergeDriver` integrates the semantic merge into Git's native `git merge` workflow.
Git invokes merge drivers on a per-file basis, but the Vitruvius merge operates on the full VSUM.
The driver handles this by running the complete semantic merge on the first invocation and caching
the result; subsequent per-file invocations copy from the cache.

**Setup** (in the VSUM repository that contains model files):

```bash
# 1. Register the merge driver
git config merge.vitruvius.name "Vitruvius semantic merge"
git config merge.vitruvius.driver "/path/to/vitruvius-merge.sh %O %A %B %L %P"

# 2. Associate model files with the driver
echo '*.model merge=vitruvius' >> .gitattributes

# 3. Create the configuration file listing your Reaction specifications
mkdir -p .vitruvius
cat > .vitruvius/merge-driver.properties <<EOF
specifications=com.example.AToB,com.example.BToA,com.example.AToC
EOF
```

The wrapper script (`vitruvius-merge.sh`) builds the Java classpath from Maven and invokes
`GitMergeDriver` with the arguments Git passes. See the BrakeCaseStudy for a working example.

**Limitations:**

- **Prototype status.** The merge driver has been tested programmatically via JUnit tests.
  End-to-end testing through `git merge` on the command line requires the full classpath
  (all Vitruvius, EMF, and project-specific dependencies) to be available to the driver script.
- **No interactive conflict resolution.** When blocking conflicts are detected, the driver
  returns a non-zero exit code and Git marks the files as conflicted, but there is no UI
  or CLI to present the semantic conflict details (element UUID, feature, both values) to the user.
  Conflicts must currently be resolved programmatically via `ConflictResolutionProvider`.
- **Merge history.** After a successful merge, Git creates a merge commit with the merged model
  files. `git blame` attributes all lines to the merge commit. The semantic changelogs
  (`.vitruvius/semantic-changelogs/`) provide richer per-transaction provenance but are not
  surfaced by standard Git tools.
- **Cold start overhead.** The first invocation bootstraps the JVM, loads EMF metamodels, and
  initializes the VSUM. This can take several seconds depending on model size.
- **Fixed Reaction set.** The merge assumes the same set of Reactions on both branches.
  Co-evolution of Reactions across branches is not supported.

## Framework-internal Dependencies

This project depends on the following other projects from the Vitruvius framework:
- [Vitruv-Change](https://github.com/vitruv-tools/Vitruv-Change)

## Module Overview

| Name         | Description                                                                                  |
|--------------|----------------------------------------------------------------------------------------------|
| views        | Definition of view types on the underlying models.                                           |
| vsum         | Definition of V-SUMs with consistency preservation rules between meta-models and view types. |
| remote       | Client-server infrastructure for working with V-SUMs.                                        |
| applications | Definition of and registry for V-SUMs.                                                       |
| *testutils*  | *Utilities for testing in Vitruvius or V-SUM projects.*                                      |
