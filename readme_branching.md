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
└── merge/                          # Semantic three-way merge
    ├── SemanticChangeLog.java      # Serialize/deserialize EChanges per commit (JSON DTOs)
    ├── ChangeLogCapture.java       # ChangePropagationListener that buffers primary changes
    ├── ChangeDtoDeserializer.java  # Reconstruct EChange<HierarchicalId> from JSON DTOs
    ├── GitStateLoader.java         # Load VSUM state from a specific Git commit
    ├── UuidConflictDetector.java   # UUID-based conflict detection on changelog DTOs
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
1. Extract base, ours, theirs model states via JGit TreeWalk into temp dirs
2. Load changelog DTOs from extracted dirs (.vitruvius/semantic-changelogs/)
3. UUID-based conflict detection on DTOs:
   - Same UUID + same feature + different values → MODIFY_MODIFY conflict
   - Delete on one branch + modify on other → DELETE_MODIFY conflict
   - Independent additions (different UUIDs) → NOT a conflict
4. If conflicts + no resolver → return CONFLICT
   If conflicts + resolver → filter DTOs by ours/theirs choice
5. Deserialize filtered DTOs into live EChange<HierarchicalId> via ChangeDtoDeserializer
6. Load target VSUM from ours state
7. Replay on a copy ResourceSet (same pattern as IdentityMappingViewType.commitViewChanges):
   a. Copy VSUM's model resources + UUID mappings into a fresh ResourceSet
   b. resolveAndApply(changes) — resolves HierarchicalId→EObject on the copy
   c. assignIds(resolved) — assigns UUIDs on the copy
   d. propagateChange(uuidChange) — applies to the VSUM, fires reactions
8. Return SemanticMergeResult (SUCCESS, CONFLICT, or SUCCESS_WITH_RESOLUTIONS)
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

- **Git merge driver not configured**: `SemanticMergeCommand` is invoked programmatically; `.gitattributes`/`.gitconfig` integration is deferred.
- **Capture requires re-registration after reload**: `ChangeLogCapture` must be re-created after `vsum.reload()` because UuidResolver changes.
- **Concurrent insert conflicts not detected**: Only MODIFY_MODIFY and DELETE_MODIFY.
- **Position-based element matching in additive merge**: New elements from theirs are identified by list index comparison (elements beyond base count). Works for appends but not reorders.
- **Rename operations require `withChangeRecordingTrait()`**: State-based `withChangeDerivingTrait()` produces CreateEObject (new UUID) instead of ReplaceSingleValuedEAttribute (same UUID), preventing conflict detection.
