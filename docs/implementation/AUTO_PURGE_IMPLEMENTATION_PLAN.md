# Auto Purge Implementation Plan

This document converts the retention and protected-recording design into an implementation sequence for BetterReplay.

It assumes the higher-level decisions already captured in [AUTO_PURGE_RETENTION_PLAN.md](../planning/AUTO_PURGE_RETENTION_PLAN.md) and resolves the remaining implementation choices needed before coding.

## Goal

Implement automatic replay retention with explicit deletion protection across both file and MySQL backends, while keeping manual deletion behavior predictable and safe.

## Frozen Decisions

The following choices are fixed for the first implementation pass:

- The protection flag name is `protectedFromDeletion`.
- Protected recordings are exempt from both retention purge and normal manual deletion.
- `protectedBy` is required when protection is enabled.
- `protectedAt` and `protectedBy` remain stored even after a replay is unprotected.
- File-backed protection metadata lives in a dedicated metadata directory, not in filesystem custom attributes.
- The file metadata location is `plugins/BetterReplay/replays-meta/<replayName>.json`.
- MySQL migration updates the existing `replays` table in place.
- There is no force-delete path in the first iteration.
- Manual deletion of a protected replay must fail with a distinct protected result.

## Recommended Delivery Strategy

Implement this as a vertical slice in the following order:

1. Add the metadata and delete-result contracts.
2. Add file-backed protection metadata and deletion enforcement.
3. Add MySQL schema migration and deletion enforcement.
4. Add retention config parsing and service lifecycle.
5. Add retention candidate scanning and purge execution.
6. Add admin/API entry points for protect and unprotect.
7. Add regression coverage and documentation updates.

This keeps the core invariants testable early:

- protected replays cannot be deleted
- protection survives backend differences
- retention uses the same protection rules as manual delete

## Ownership Boundaries

### `Replay`

Owns service lifecycle.

Responsibilities:

- initialize storage
- initialize and start the retention service after storage is ready
- stop the retention service during plugin disable

### `ReplayManager` and `ReplayManagerImpl`

Own the public management API and cache refresh orchestration.

Responsibilities:

- expose protect, unprotect, delete, and listing operations to callers
- translate storage results into public API results
- refresh the replay-name cache after successful delete operations

### `ReplayStorage`

Owns stored-replay metadata access and backend-level enforcement.

Responsibilities:

- provide lightweight replay summaries for retention
- store and retrieve deletion-protection metadata
- reject deletion of protected recordings

Protection must be enforced in storage, not only in the retention service or command layer. That prevents accidental bypass if another caller deletes directly through the storage abstraction.

### `ReplayRetentionService`

Owns periodic purge policy.

Responsibilities:

- read and validate retention settings
- schedule periodic async scans
- fetch replay summaries from storage
- filter by age and `protectedFromDeletion`
- delete expired candidates through the normal storage delete path
- log summary results and failures

### File-storage metadata helper

Add a small helper dedicated to file-backed protection metadata. Recommended name:

- `FileReplayProtectionStore`

Responsibilities:

- read protection metadata from `replays-meta`
- write metadata updates
- delete metadata when the replay itself is deleted

This keeps `FileReplayStorage` from accumulating path, JSON, and metadata-merging logic.

## Data Contract Changes

### New summary model

Add a lightweight replay metadata record. Recommended shape:

```java
public record ReplaySummary(
        String name,
        Instant createdAt,
        long sizeBytes,
        boolean protectedFromDeletion,
        Instant protectedAt,
        String protectedBy,
        ReplayStorageType storageType
) {}
```

Semantics:

- `createdAt` is the timestamp used by retention
- `protectedFromDeletion` blocks both retention and normal delete
- when `protectedFromDeletion` is `true`, both `protectedAt` and `protectedBy` must be present
- when `protectedFromDeletion` is `false`, `protectedAt` and `protectedBy` may still be present as historical audit values

### New delete result model

Replace boolean delete outcomes with an explicit result. Recommended shape:

```java
public enum ReplayDeleteResult {
        DELETED,
        NOT_FOUND,
        PROTECTED
}
```

This removes the current ambiguity where `false` means either not found or not allowed.

### New protection update result model

Add a small result enum for protect and unprotect operations. Recommended shape:

```java
public enum ReplayProtectionResult {
        UPDATED,
        NOT_FOUND,
        ALREADY_PROTECTED,
        ALREADY_UNPROTECTED
}
```

This gives commands and callers stable behavior without relying on side effects or custom error strings.

### Storage interface changes

Recommended additions to `ReplayStorage`:

```java
CompletableFuture<List<ReplaySummary>> listReplaySummaries();
CompletableFuture<ReplayDeleteResult> deleteReplay(String name);
CompletableFuture<ReplayProtectionResult> protectReplay(String name, Instant protectedAt, String protectedBy);
CompletableFuture<ReplayProtectionResult> unprotectReplay(String name);
```

Keep `listReplays()` for name-only UI and cache use.

### Public API changes

Recommended changes to `ReplayManager` and `ReplayManagerImpl`:

```java
CompletableFuture<List<ReplaySummary>> listSavedReplaySummaries();
CompletableFuture<ReplayDeleteResult> deleteSavedReplay(String name);
CompletableFuture<ReplayProtectionResult> protectSavedReplay(String name, String protectedBy);
CompletableFuture<ReplayProtectionResult> unprotectSavedReplay(String name);
```

`protectSavedReplay` should capture `Instant.now()` inside the manager implementation so callers only provide the actor.

Because `ReplayManager` is part of the public API surface, this phase also requires updating `docs/API.md` when code ships.

## Phase 1: Contracts and Shared Models

Deliverables:

- shared result enums for delete and protect operations
- shared `ReplaySummary` record
- updated storage and manager interfaces

Implementation tasks:

- add `ReplaySummary`
- add `ReplayDeleteResult`
- add `ReplayProtectionResult`
- update `ReplayStorage`
- update `ReplayManager`
- update `ReplayManagerImpl` method signatures and result handling
- update any helper classes that currently call `deleteReplay(String)` directly

Touch points:

- `src/main/java/me/justindevb/replay/storage/ReplayStorage.java`
- `src/main/java/me/justindevb/replay/api/ReplayManager.java`
- `src/main/java/me/justindevb/replay/ReplayManagerImpl.java`
- `src/main/java/me/justindevb/replay/util/ReplayObject.java`

Exit criteria:

- the codebase can represent protected-delete semantics without overloading boolean values

## Phase 2: File Backend Protection Metadata

Deliverables:

- file-backed protection metadata persisted in `replays-meta`
- file-backed delete path rejects protected recordings
- file-backed summary listing merges replay artifact info with protection metadata

Implementation tasks:

- add `FileReplayProtectionStore`
- store metadata files under `plugins/BetterReplay/replays-meta/<replayName>.json`
- use the replay name as the metadata stem, matching the existing replay-name-to-filename convention in `FileReplayStorage`
- create metadata on protect if it does not exist
- on unprotect, set `protectedFromDeletion` to `false` but leave `protectedAt` and `protectedBy` untouched
- on delete success, remove both the replay artifact and its metadata file

Recommended metadata shape:

```json
{
  "protectedFromDeletion": true,
  "protectedAt": "2026-04-29T12:00:00Z",
  "protectedBy": "console"
}
```

Rationale for one file per replay:

- replay-scoped metadata updates stay replay-scoped instead of rewriting a shared index file
- corruption or partial writes only affect one replay's protection state
- delete cleanup is simple because the replay artifact and its metadata can be removed together
- the file-backed model more closely mirrors the MySQL row-level metadata design

Rationale against a single global JSON metadata file:

- every protect, unprotect, and delete would rewrite the same shared file
- concurrent retention and admin operations would need stronger locking around that shared file
- one malformed or truncated file would put every replay's protection metadata at risk

Rationale against the replay manifest as the source of truth:

- `protectedFromDeletion` is local storage policy, not replay-format compatibility metadata
- toggling the flag would require rewriting the replay archive instead of a small sidecar file
- the flag would unintentionally travel with copied or exported replay artifacts

### File `createdAt` source

Use the most precise lightweight source available:

- for `.br` archives, read `recordingStartedAtEpochMillis` from `manifest.json` without loading the full replay timeline
- for legacy JSON replay files, use filesystem last-modified time as the first-iteration fallback

The legacy JSON fallback is acceptable because legacy JSON is already transitional in this repository and the retention implementation should not block on inventing a new JSON-specific metadata format.

Recommended helper addition:

- add a lightweight manifest-inspection path for binary archives rather than reusing full replay decode

Touch points:

- `src/main/java/me/justindevb/replay/storage/FileReplayStorage.java`
- new helper under `src/main/java/me/justindevb/replay/storage/`
- binary metadata reader helper under `src/main/java/me/justindevb/replay/storage/binary/`

Exit criteria:

- protecting a file-backed replay creates durable metadata
- unprotecting leaves `protectedAt` and `protectedBy` intact
- deleting a protected file-backed replay returns `PROTECTED`

## Phase 3: MySQL Schema Migration and Backend Support

Deliverables:

- existing `replays` table migrated in place
- MySQL-backed delete path rejects protected rows
- MySQL-backed summary listing includes protection metadata

Implementation tasks:

- extend `MySQLReplayStorage.init()` to perform additive migration for existing tables
- add these columns:

```sql
is_protected BOOLEAN NOT NULL DEFAULT FALSE
protected_at TIMESTAMP NULL
protected_by VARCHAR(64) NULL
```

- if the deployed MySQL version does not support `ADD COLUMN IF NOT EXISTS`, probe `INFORMATION_SCHEMA.COLUMNS` first and alter only missing columns
- preserve the existing `created_at` column as the retention timestamp source
- implement `protectReplay` as an update that sets:
  - `is_protected = TRUE`
  - `protected_at = ?`
  - `protected_by = ?`
- implement `unprotectReplay` as an update that sets:
  - `is_protected = FALSE`
  - `protected_at` unchanged
  - `protected_by` unchanged
- implement delete as:
  - load row existence and protection state
  - return `NOT_FOUND` if absent
  - return `PROTECTED` if `is_protected = TRUE`
  - otherwise delete and return `DELETED`

Touch points:

- `src/main/java/me/justindevb/replay/storage/MySQLReplayStorage.java`

Exit criteria:

- MySQL protection behaves the same as file protection
- existing servers upgrade in place without manual schema steps

## Phase 4: Retention Config and Lifecycle Wiring

Deliverables:

- config-driven retention settings
- retention service created and stopped with the plugin lifecycle

Implementation tasks:

- add new config settings to `ReplayConfigSetting`:
  - `Retention.Enabled`
  - `Retention.Max-Age`
  - `Retention.Check-Interval`
  - `Retention.Delete-Partial-Failures`
  - `Retention.Log-Deletions`
- bump the config version from `2` to `3`
- add comments for the new retention keys in `ReplayConfigManager`
- add a small duration parser/helper for values like `30d`, `1h`, `15m`, and `30s`
- create a `RetentionPolicy` record from validated config values
- instantiate `ReplayRetentionService` in `Replay.onEnable()` after `initStorage()`
- stop or cancel the service in `Replay.onDisable()`

Touch points:

- `src/main/java/me/justindevb/replay/config/ReplayConfigSetting.java`
- `src/main/java/me/justindevb/replay/config/ReplayConfigManager.java`
- `src/main/java/me/justindevb/replay/Replay.java`
- new retention classes under `src/main/java/me/justindevb/replay/` or `src/main/java/me/justindevb/replay/storage/`

Exit criteria:

- retention can be enabled or disabled purely from config
- the service is not scheduled when disabled

## Phase 5: Retention Execution

Deliverables:

- periodic async retention scan
- backend-agnostic expired-candidate filtering
- summary logging for each retention run

Implementation tasks:

- implement `ReplayRetentionService`
- schedule scans on FoliaLib's async scheduler
- compute `cutoff = now - maxAge`
- fetch `listReplaySummaries()` from the active backend
- filter candidates where:
  - `createdAt` is before cutoff
  - `protectedFromDeletion` is `false`
- delete candidates through the normal `deleteReplay` path
- treat a `PROTECTED` delete result during purge as a non-fatal race or stale-summary condition and log it at debug or warning level
- log counts for:
  - scanned replays
  - expired candidates
  - deleted replays
  - skipped protected replays
  - failures

Recommended behavior:

- do not touch Bukkit API inside the purge loop
- do not stop the whole service because one delete failed
- honor `Retention.Delete-Partial-Failures` for whether to continue within the current run after a failure

Touch points:

- new retention service class
- `Replay.java` lifecycle wiring

Exit criteria:

- retention deletes expired unprotected recordings on both backends
- protected recordings are always skipped

## Phase 6: Manager and Command Integration

Deliverables:

- admin-visible protect and unprotect operations
- delete command reports protected status distinctly

Recommended command surface:

- `/replay protect <name>`
- `/replay unprotect <name>`

Implementation tasks:

- add manager methods for protect and unprotect
- implement command handlers in `ReplayCommand`
- add help-text entries and permissions for protect and unprotect
- allow these subcommands from both player and console senders
- set `protectedBy` as:
  - player name for player senders
  - `console` for console senders
- update `/replay delete <name>` messaging:
  - `DELETED` -> success message
  - `NOT_FOUND` -> replay not found message
  - `PROTECTED` -> replay is protected and must be unprotected before deletion

No force-delete subcommand or command flag should be added in this iteration.

Touch points:

- `src/main/java/me/justindevb/replay/ReplayCommand.java`
- `src/main/resources/plugin.yml`
- `src/main/java/me/justindevb/replay/ReplayManagerImpl.java`

Exit criteria:

- admins can protect and unprotect recordings without editing storage by hand
- delete command behavior matches the protection rules

## Phase 7: Regression Tests

Deliverables:

- unit and regression coverage for delete protection, metadata persistence, migration, and retention filtering

Implementation tasks by existing test surface:

- `src/test/java/me/justindevb/replay/storage/FileReplayStorageTest.java`
  - protect writes metadata file
  - unprotect keeps `protectedAt` and `protectedBy`
  - delete protected replay returns `PROTECTED`
  - delete successful replay removes metadata file
  - summary listing merges replay metadata and protection metadata
- `src/test/java/me/justindevb/replay/storage/FileReplayStorageEdgeCaseTest.java`
  - missing metadata file defaults to unprotected
  - malformed metadata file is logged and treated as failure or safe skip, depending on chosen behavior
- `src/test/java/me/justindevb/replay/storage/MySQLReplayStorageTest.java`
  - init migrates existing table
  - protect updates `is_protected`, `protected_at`, and `protected_by`
  - unprotect clears only the boolean flag
  - delete protected row returns `PROTECTED`
  - list summaries includes protection fields
- `src/test/java/me/justindevb/replay/ReplayManagerImplTest.java`
  - delete result propagation
  - protect/unprotect API delegation
  - cache refresh only after successful delete
- `src/test/java/me/justindevb/replay/ReplayCommandTest.java`
  - protect command success and failure cases
  - unprotect command success and failure cases
  - delete command shows protected-specific message
- add a new retention service test class
  - disabled retention does not schedule
  - expired protected replay is skipped
  - expired unprotected replay is deleted
  - delete failures do not crash later runs

Exit criteria:

- a regression test fails if any backend allows deletion of a protected replay

## Phase 8: Documentation and Release Hygiene

Deliverables:

- user-facing docs updated for retention and protection commands/config
- API docs updated for manager changes
- changelog entry prepared under `Unreleased`

Implementation tasks:

- update `README.md` with:
  - retention config
  - protect and unprotect commands
  - manual delete behavior for protected recordings
- update `docs/API.md` with the manager API changes and examples
- add a `CHANGELOG.md` entry under `Unreleased`

Exit criteria:

- docs match shipped behavior and public API changes

## Recommended First PR Breakdown

If this work is split across multiple pull requests, use this order:

1. Contract changes plus file-backed protection metadata.
2. MySQL migration and backend parity.
3. Retention config and service execution.
4. Command surface, API docs, README, and changelog.

This ordering gets the storage invariant in place first, which is the highest-risk part of the feature.