# Auto Purge Retention Plan

This document proposes a storage-agnostic retention feature that automatically deletes old recordings after a configured age. The goal is to prevent unbounded growth in replay storage for both file-backed and MySQL-backed deployments.

## Goal

Add an optional retention policy that automatically removes recordings older than a configured time window.

Examples:

- a server using file storage keeps only the last 14 days of replays on disk
- a server using MySQL keeps only the last 30 days of replay rows and payload data
- a server with retention disabled keeps the current behavior and never auto-deletes recordings

## Non-Goals for the First Iteration

- No per-player or per-world retention rules
- No size-based cleanup policy such as "delete until under 20 GB"
- No archive tier or cold-storage migration
- No recycle-bin or soft-delete state
- No admin command to bulk purge arbitrary recordings by ad hoc filters

## Problem Statement

Replay data naturally accumulates over time.

- File storage can consume large amounts of disk space through many replay files.
- MySQL storage can accumulate large rows or blobs and degrade maintenance operations over time.
- Servers that enable automated recording are especially likely to create many recordings per day.

Without a retention mechanism, cleanup is manual and easy to forget. The plugin should provide a predictable and explicit cleanup policy owned by configuration.

## Recommended Feature Shape

### 1. Retention Is Config-Driven

Retention should be enabled through config rather than a command-only workflow.

Recommended shape:

```yaml
Retention:
  Enabled: false
  Max-Age: 30d
  Check-Interval: 1h
  Delete-Partial-Failures: false
  Log-Deletions: true
```

Recommended semantics:

- `Enabled`: master switch for automatic purge
- `Max-Age`: recordings older than this age become purge candidates
- `Check-Interval`: how often the cleanup task scans for expired recordings
- `Delete-Partial-Failures`: whether the task should continue deleting other candidates after one delete fails
- `Log-Deletions`: whether successful deletions are logged at info level

`Max-Age` and `Check-Interval` should accept a human-readable duration format such as `30d`, `12h`, `90m`, or `3600s`.

### 2. Storage-Agnostic Retention Service

Introduce a dedicated coordinator, for example:

- `ReplayRetentionService`

Responsibilities:

- parse and validate retention config
- schedule periodic retention scans
- compute the expiration cutoff timestamp
- list purge candidates from the active storage backend
- delete expired recordings through the existing storage abstraction
- log summary results and failures

The service should own policy and scheduling. Storage implementations should only expose the primitives needed to enumerate and delete recordings.

## Proposed Runtime Design

### Retention Policy Model

Represent retention settings as an immutable config model, for example:

```java
public record RetentionPolicy(
        boolean enabled,
        Duration maxAge,
        Duration checkInterval,
        boolean continueAfterDeleteFailure,
        boolean logDeletions
) {}
```

This gives one validated object that can be passed to the scheduler and tests.

### Backend Abstraction Changes

The current storage API already supports deleting named recordings, but retention needs a way to discover old recordings by creation time.

Recommended addition to the storage contract:

```java
Collection<ReplaySummary> listReplaySummaries();
```

Where `ReplaySummary` contains only lightweight metadata required for retention decisions:

```java
public record ReplaySummary(
        String name,
        Instant createdAt,
        long sizeBytes,
        ReplayStorageType storageType
) {}
```

Notes:

- `createdAt` must be the canonical age field used by retention.
- `sizeBytes` is optional for first-iteration logic but useful for logging and future size-based policies.
- the summary model should avoid loading full replay payloads into memory.

### Candidate Selection

On each retention run:

1. Read the current wall-clock time.
2. Compute `cutoff = now - maxAge`.
3. Ask the active storage backend for replay summaries.
4. Select recordings where `createdAt < cutoff`.
5. Delete each candidate through the normal delete path.
6. Emit a summary log line with candidate count, deleted count, and failure count.

The first iteration should use a simple full scan. If large installations later need pagination or SQL-side filtering, that can be optimized behind the same abstraction.

### Scheduling Model

Retention scanning should be periodic and server-owned.

Recommended behavior:

- start the retention service during plugin enable after storage initialization completes
- skip scheduling entirely when retention is disabled
- cancel and recreate the task on plugin reload if config changes are supported
- run storage scans and deletes off the main server thread
- route any Bukkit-facing logging or notifications through the existing safe execution model when needed

Because this feature only touches storage and logging, it should avoid Bukkit API work inside the purge loop.

## Storage Backend Behavior

### File Storage

File-backed retention should:

- enumerate replay files without loading full replay contents
- derive `createdAt` from canonical replay metadata, not file last-modified time unless that is the documented source of truth
- delete the replay file and any sidecar metadata files atomically as far as practical
- treat missing files as a handled warning rather than a fatal scheduler error

Important detail:

Retention should not rely on filesystem timestamps unless the replay format explicitly guarantees that they represent recording creation time. If the replay manifest already stores creation time, that should be the source of truth.

### MySQL Storage

MySQL-backed retention should:

- query only the metadata needed to identify expired recordings
- prefer deleting by primary key or unique replay name through the storage adapter
- keep all deletion logic inside the storage implementation so schema details stay encapsulated
- avoid long-running monolithic transactions if many recordings are expired at once

If the storage schema already stores creation timestamp in a replay metadata table, the retention scan can filter candidates there before calling the delete path.

## Deletion Semantics and Safety

### What Counts as Expired

A replay is expired when:

```text
createdAt < now - configuredMaxAge
```

Use strict less-than so a replay exactly on the boundary is retained until the next run.

### Failure Handling

Deletion should be best-effort.

Recommended rules:

- one failed delete must not disable the service permanently
- failures should be logged with replay name and exception context
- the service should continue or stop based on the configured `Delete-Partial-Failures` policy
- the next scheduled run should retry any still-expired recordings naturally

### Active Recording Protection

Retention must never delete recordings that are still being written.

Recommended safeguards:

- only finalized recordings should appear in `listReplaySummaries()`
- active in-memory sessions should not be visible to the retention service
- partially written temporary files or staging rows should remain out of scope for first-iteration retention

This keeps retention separated from recording finalization and avoids deleting incomplete artifacts.

## Config and Admin Experience

### Config Defaults

Recommended defaults:

- `Retention.Enabled: false`
- `Retention.Max-Age: 30d`
- `Retention.Check-Interval: 1h`
- `Retention.Delete-Partial-Failures: false`
- `Retention.Log-Deletions: true`

Rationale:

- disabled by default preserves current behavior for existing servers
- 30 days is a reasonable documented example even when inactive
- hourly scans are frequent enough without creating unnecessary load

### Validation Rules

Config load should reject or clamp clearly invalid values.

Recommended validation:

- `Max-Age` must be positive
- `Check-Interval` must be positive
- `Check-Interval` should have a sane minimum such as 5 minutes to avoid accidental tight loops
- invalid duration strings should log a clear warning and fall back to safe defaults

### Visibility

The first iteration does not require a full command surface, but an admin-visible status line is useful.

Possible future command:

```text
/replay retention status
```

This can remain out of scope for the first implementation if config and logs are sufficient.

## Testing Plan

This feature needs both unit coverage and regression coverage.

### Core Service Tests

- retention disabled does not schedule a task
- expired recordings are selected using `createdAt` and `maxAge`
- boundary-age recordings are not deleted early
- delete failures are logged and do not crash future runs
- continue-versus-stop behavior matches config after a deletion failure

### File Storage Tests

- file backend lists replay summaries without loading full payloads
- expired file-backed recordings are deleted through the storage adapter
- missing file artifacts are handled as warnings

### MySQL Storage Tests

- MySQL backend exposes replay summaries with correct timestamps
- expired database recordings are deleted through the adapter contract
- backend-specific query failures propagate as handled retention failures

### Regression Cases

- finalized recordings are purgeable, active sessions are not
- retention disabled preserves old recordings indefinitely
- very small retention windows still respect the strict boundary rule

## Documentation Impact

When implemented, the following docs will need updates:

- `README.md` for the new retention config section
- `CHANGELOG.md` under `Unreleased`
- `docs/API.md` only if retention becomes part of the public API

## Open Questions

1. What field is the canonical recording creation timestamp for file-backed storage: manifest metadata, filename encoding, or filesystem metadata?
2. Does the MySQL schema already expose replay creation timestamps in a lightweight queryable table, or does that need to be added?
3. Should retention support an exclusion marker for recordings that admins want to keep permanently?
4. Should the service emit metrics or benchmark hooks for large purge runs?

## Recommended First Implementation Order

1. Add validated retention config parsing and a small `RetentionPolicy` model.
2. Extend the storage abstraction with lightweight replay summary enumeration.
3. Implement `ReplayRetentionService` with periodic scheduling and summary logging.
4. Add backend-specific summary enumeration for file and MySQL storage.
5. Add regression tests covering selection, deletion, failures, and active-recording safety.
6. Update `README.md` and `CHANGELOG.md` when code implementation begins.