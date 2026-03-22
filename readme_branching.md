# Vitruvius Branching & Semantic Merge — Developer Guide

This document describes the Git-aware branching and semantic three-way merge features added to the Vitruv framework. These features allow Vitruvius-managed V-SUMs to stay consistent across Git branches and to merge branches semantically rather than textually.

## Overview

The branching system has three layers:

1. **Branch Management** — Create, switch, delete branches via `BranchManager` (JGit-based). Metadata tracked in `.vitruvius/branches/`.
2. **Git Hook Integration** — Four hooks (pre-commit, post-checkout, post-commit, post-merge) communicate with background Java watchers via trigger files.
3. **Semantic Three-Way Merge** — Replays serialized EChange transactions from one branch onto another, preserving model consistency through Vitruv's propagation engine.

## Package Structure

All branching code is in `vsum/src/main/java/tools/vitruv/framework/vsum/branch/`:

```
branch/
├── BranchManager.java              # Git branch CRUD via JGit
├── data/
│   ├── BranchMetadata.java         # Branch lifecycle (name, uid, state, parent, timestamps)
│   ├── BranchState.java            # ACTIVE, MERGED, DELETED
│   ├── SemanticChangelog.java       # Git commit metadata changelog
│   ├── FileChange.java / FileOperation.java
│   └── ValidationResult.java
├── handler/
│   ├── PostCheckoutHandler.java    # Calls VirtualModel.reload() on branch switch
│   ├── PreCommitHandler.java       # Validates VSUM before commit
│   ├── PostCommitHandler.java      # Generates changelog + persists semantic change log
│   ├── PostMergeHandler.java       # Validates VSUM after merge
│   ├── VsumReloadWatcher.java      # Polls for reload triggers (500ms)
│   ├── VsumValidationWatcher.java  # Polls for validation triggers
│   ├── VsumPostCommitWatcher.java  # Polls for post-commit triggers
│   └── VsumMergeWatcher.java       # Polls for merge triggers
├── util/
│   ├── GitHookInstaller.java       # Installs/uninstalls Git hook scripts
│   ├── AbstractTriggerFile.java    # Base for file-based IPC
│   ├── ReloadTriggerFile.java      # post-checkout → watcher
│   ├── ValidationTriggerFile.java  # pre-commit → watcher
│   ├── ValidationResultFile.java   # watcher → pre-commit result
│   ├── PostCommitTriggerFile.java  # post-commit → watcher
│   ├── MergeTriggerFile.java       # post-merge → watcher
│   └── MergeResultFile.java        # watcher → post-merge result
└── merge/                          # NEW: Semantic three-way merge
    ├── SemanticChangeLog.java      # Serialize/deserialize EChanges per commit (JSON)
    ├── ChangeLogCapture.java       # ChangePropagationListener that buffers primary changes
    ├── ChangeExtractor.java        # Read changelogs for a commit range
    ├── GitStateLoader.java         # Load VSUM state from a specific Git commit
    ├── ConflictDetector.java       # Element-level conflict detection
    ├── MergeConflict.java          # Conflict data class
    ├── SemanticMergeResult.java    # Merge outcome data class
    ├── SemanticMergeEngine.java    # Core three-way merge algorithm
    └── SemanticMergeCommand.java   # CLI / programmatic entry point
```

## How Branch Switching Works

### Path 1: Programmatic (API)

```java
BranchManager branchManager = new BranchManager(repoRoot);
branchManager.setPostCheckoutHandler(new PostCheckoutHandler(vsum));
branchManager.createBranch("feature-x", "main");
branchManager.switchBranch("feature-x");
// VSUM is now reloaded with the feature-x branch state
```

### Path 2: CLI (native Git commands)

```
git checkout feature-x
  → .git/hooks/post-checkout fires
  → writes .vitruvius/reload-trigger
  → VsumReloadWatcher detects trigger (polls every 500ms)
  → PostCheckoutHandler.onBranchSwitch()
  → VirtualModel.reload()
    → discards all in-memory models
    → reloads XMI files, UUID mappings, correspondences from disk
  → trigger file deleted
```

## How Pre-Commit Validation Works

```
git commit
  → .git/hooks/pre-commit fires
  → writes .vitruvius/validate-trigger (with unique request ID)
  → VsumValidationWatcher detects trigger
  → PreCommitHandler.validate() checks:
    1. All model resources loadable (valid XMI)
    2. No unresolved proxies
    3. Correspondence model accessible
    4. UUID resolver accessible
  → writes result to .vitruvius/validation-result-<requestId>
  → hook reads result, allows or blocks commit
```

## How Semantic Changelog Capture Works

When `view.commitChanges()` is called, it triggers `VirtualModel.propagateChange()`. A registered `ChangeLogCapture` listener intercepts these calls:

1. `startedChangePropagation()` — saves reference to the input `VitruviusChange<Uuid>`
2. `finishedChangePropagation()` — after changes are applied:
   - Converts `EChange<Uuid>` → `EChange<HierarchicalId>` via UuidResolver + HierarchicalIdResolver
   - Buffers the HierarchicalId-based changes
3. On commit, `VsumPostCommitWatcher` drains the buffer and persists via `SemanticChangeLog.saveTo()`

**Important**: After `vsum.reload()`, the UuidResolver is recreated. The `ChangeLogCapture` must be re-registered with the new resolver:

```java
vsum.removeChangePropagationListener(capture);
capture = ChangeLogCapture.create(vsum.getUuidResolver(),
    vsum.getViewSourceModels().iterator().next().getResourceSet());
vsum.addChangePropagationListener(capture);
```

## Semantic Change Log Format

Changelogs are stored in `.vitruvius/semantic-changelogs/<shortSha>.changelog` as JSON:

```json
{
  "commitSha": "a1b2c3d4e5f6...",
  "branch": "feature-x",
  "changes": [
    {
      "changeType": "CreateEObject",
      "affectedElementId": "cache://...",
      "affectedEObjectType": "Component"
    },
    {
      "changeType": "InsertEReference",
      "affectedElementId": "/0",
      "featureName": "components",
      "newValueId": "cache://...",
      "index": 1
    },
    {
      "changeType": "ReplaceSingleValuedEAttribute",
      "affectedElementId": "/0/@components.1",
      "featureName": "name",
      "newLiteralValue": "FeatureComponent"
    }
  ]
}
```

Each DTO captures the **primary user changes** only — derived changes (from reactions/propagation) are regenerated during replay.

## How Semantic Three-Way Merge Works

### Algorithm

```
1. Find merge base (common ancestor) via JGit RevWalk
2. Extract source-branch changes: read changelogs from base..theirs
3. Extract target-branch changes: read changelogs from base..ours
4. Detect conflicts: both branches modify same element → CONFLICT
5. If no conflicts:
   a. Clone target state into temp directory
   b. Load fresh VSUM from target state
   c. Replay source changes via propagateChange()
      → reactions fire, correspondences maintained
   d. Save merged state
6. Return SemanticMergeResult (SUCCESS or CONFLICT)
```

### Usage

```java
SemanticMergeCommand cmd = new SemanticMergeCommand();
SemanticMergeResult result = cmd.execute(
    repoRoot, "feature-x", "main",
    List.of(new MyChangePropagationSpecification()),
    new TestUserInteraction.ResultProvider(new TestUserInteraction()));

if (result.isSuccess()) {
    // Merged state is at result.getMergedStateFolder()
} else {
    for (MergeConflict conflict : result.getConflicts()) {
        System.out.println("Conflict on element: " + conflict.getElementId());
    }
}
```

### Conflict Types

| Type | Description |
|------|-------------|
| `MODIFY_MODIFY` | Both branches modify the same element |
| `DELETE_MODIFY` | Target deletes, source modifies |
| `MODIFY_DELETE` | Target modifies, source deletes |

## Git Hooks

Four hooks in `vsum/src/main/resources/git-hooks/`:

| Hook | Blocking? | Purpose |
|------|-----------|---------|
| `pre-commit` | Yes (blocks on failure) | Validates VSUM consistency before commit |
| `post-checkout` | No | Triggers VSUM reload on branch switch |
| `post-commit` | No (fire-and-forget) | Generates changelog + semantic change log |
| `post-merge` | No (warnings only) | Validates VSUM after merge, detects conflict markers |

Install hooks via:
```java
GitHookInstaller installer = new GitHookInstaller(repoRoot);
installer.installAllHooks();
```

## Data Files (`.vitruvius/` directory)

| Path | Purpose |
|------|---------|
| `.vitruvius/branches/<name>.metadata` | Branch lifecycle (key=value) |
| `.vitruvius/changelogs/<sha>.txt` | Git metadata changelog (human-readable) |
| `.vitruvius/semantic-changelogs/<sha>.changelog` | EChange-level changelog (JSON) |
| `.vitruvius/merges/<sha>.metadata` | Merge audit trail |
| `.vitruvius/reload-trigger` | Post-checkout IPC trigger |
| `.vitruvius/validate-trigger` | Pre-commit IPC trigger |
| `.vitruvius/post-commit-trigger` | Post-commit IPC trigger |
| `.vitruvius/merge-trigger` | Post-merge IPC trigger |
| `.vitruvius/.reload.lock` / `.validation.lock` | OS-level file locks |

## Building and Testing

```bash
# Build Vitruv with merge code:
cd /workspace/Vitruv && ./mvnw clean install -Dmaven.test.skip=true

# Run branching unit tests in Vitruv:
cd /workspace/Vitruv && ./mvnw -pl vsum test

# Run semantic merge integration test:
cd /workspace/Vitruvius-Branching-Test && ./mvnw -pl vsum test -Dtest=SemanticMergeIntegrationTest

# Run all tests in test project:
cd /workspace/Vitruvius-Branching-Test && ./mvnw -pl vsum verify
```

## Key Dependencies

- **JGit** (`org.eclipse.jgit`) — Git operations
- **EMF** (ECore, XMI) — Model persistence
- **Vitruv Change** — EChange metamodel, ChangeRecorder, ChangePropagator, UuidResolver
- **Gson** — JSON serialization for semantic changelogs

## Known Limitations (Prototype)

- **Replay from DTOs not yet implemented**: Changelogs are captured and persisted, but deserializing DTOs back into live `EChange<HierarchicalId>` objects for `propagateChange()` replay is the next step.
- **Git merge driver not configured**: `SemanticMergeCommand` is invoked programmatically; `.gitattributes`/`.gitconfig` integration is deferred.
- **No interactive conflict resolution**: Merge aborts on conflict, reports only.
- **Capture requires re-registration after reload**: `ChangeLogCapture` must be re-created after `vsum.reload()` because UuidResolver changes.
- **Concurrent insert conflicts not detected**: Only MODIFY_MODIFY and DELETE_MODIFY.
