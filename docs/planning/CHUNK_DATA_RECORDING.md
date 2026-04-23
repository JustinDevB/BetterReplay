# Chunk Data Recording Design

This document proposes a replay extension that captures world chunk state alongside timeline events.
The goal is to make replays portable across servers and improve visual fidelity by reproducing the recorded world state, not just entity movement.

## Problem Statement

Current recordings primarily capture entity and interaction events. Playback correctness depends on the live world at replay time.

That creates gaps:

- A replay played on another server may have different terrain, blocks, or builds.
- A replay played later on the same server may no longer match original world state.
- Fast-moving players can cross many chunks, making world divergence more visible.

## Goals

- Add a config option to enable or disable chunk data recording.
- Add a configurable chunk radius around each tracked player.
- Continuously discover needed chunks as tracked players move.
- Support many tracked players in one recording without redundant chunk snapshots.
- Use one long-term replay container strategy that does not require future format forks.
- Keep optional chunk capture while preserving replay portability.
- Restore real server chunk data to viewers when replay ends.

## Non-Goals

- Replacing the server's world files on disk.
- Supporting random third-party chunk formats in v1.
- Storing chunk delta streams in the chunk section (timeline events remain the source of block changes).

## Configuration Proposal

```yaml
Recording:
  Chunk-Capture:
    Enabled: false
    Radius: 1
    Capture-Interval-Ticks: 20
    Max-Unique-Chunks-Per-Recording: 20000
```

### Key behavior

- `Recording.Chunk-Capture.Enabled`
  - `false`: write `.br` (no chunk baseline section).
  - `true`: write `.brc` (includes chunk baseline section).
- `Recording.Chunk-Capture.Radius`
  - Radius in chunk units around each tracked player.
  - Default is `1` to minimize file size and bandwidth.
  - Example: `1` captures a `3 x 3` chunk square per player.
- `Recording.Chunk-Capture.Capture-Interval-Ticks`
  - Interval for recomputing the chunk interest union.
- `Recording.Chunk-Capture.Max-Unique-Chunks-Per-Recording`
  - Guardrail to cap capture size on large roaming sessions.

## Recording Model

### 1. Dynamic chunk interest set

On each capture interval:

1. Read all tracked player chunk coordinates.
2. Build the union of chunk coordinates in each player's configured radius.
3. Diff against the currently known chunk set.
4. Capture baseline for newly discovered chunks.

### 2. Chunk baseline capture

Capture chunk data once per unique `(worldId, chunkX, chunkZ)`:

- Store baseline payload for first observation only.
- If chunk leaves and re-enters range, reuse existing baseline.
- Do not write chunk deltas into the chunk section.

All world modifications after baseline are reconstructed from timeline events (`BlockPlace`, `BlockBreak`, `BlockBreakStage`, and related events) in tick order.

### 3. Multi-player scaling

Use one shared per-recording chunk registry:

- Deduplicate chunk baselines across players.
- Player movement updates references, not payload duplication.
- Enforce maximum unique chunk cap.

## Storage Plan

Adopt one canonical binary BetterReplay container for all future replays. Both file and MySQL backends store this same container payload.

### File extensions

- `.br`: BetterReplay container without chunk baseline section.
- `.brc`: BetterReplay container with chunk baseline section.

The extension indicates features; the core container family is shared and versioned.

### Container layout

1. Header (magic, format version, feature flags).
2. Timeline section (binary timeline blocks).
3. Chunk section (optional; present for `.brc`).
4. Section index (offset, length, codec, lookup metadata).
5. Footer/checksum.

### Compression

- Default: LZ4 block compression for timeline and chunk section blocks.
- Timeline and chunk entries are independently addressable.
- Reader decompresses only needed blocks/entries on demand.
- Already-compressed payloads may be stored without recompression if that is smaller/faster.

### Logical internal view

```
MyReplay.brc
  header
  timeline_blocks
  chunk_entries
  section_index
  footer
```

This is an internal container layout, not a set of separate files on disk.

### Backend mapping

File backend:

- One file per replay: `name.br` or `name.brc`.
- Delete operation is a single file remove.

MySQL backend:

- One row per replay with one container blob column.
- Delete operation is a single row remove.

No separate chunk table is required.

## Playback Behavior

### Replay start

When replay data includes chunk payloads:

1. Load or map chunk index for requested replay segment.
2. For the replay viewer(s), send chunk data packets from the recording source.
3. Start timeline playback after required chunk baseline is available.

### During replay

- Apply block changes in tick order as they appear in the timeline (`BlockPlace`, `BlockBreak`, `BlockBreakStage` events).
- The baseline chunks plus timeline edits reconstruct the full world state at any playback tick.

### Chunk availability vs active loading

Captured chunks in a `.brc` recording are treated as **available data**, not always-loaded data.

- If multiple recorded players are far apart (for example 1000+ blocks), chunks for all areas can exist in the recording.
- During playback, the watcher only receives chunks inside their current playback window.
- Chunks outside that window remain indexed and available, but are not decompressed or sent yet.
- If the watcher moves to a distant recorded area later, those chunks are then loaded on demand and sent.

In short: capture scope determines what can be shown; watcher position determines what is loaded now.

### Replay end

When replay stops, for every chunk overridden by replay playback:

1. Mark chunk coordinates as dirty in replay session state.
2. Send the real server chunk data back to affected viewers.
3. Clear replay chunk overrides and viewer tracking state.

This guarantees viewers return to live world state after replay completion.

## Failure and Safety Considerations

- If chunk entries are missing/corrupt, warn and fall back to entity-only playback (no chunk overlay).
- Enforce maximum chunk count and total directory size limits to prevent runaway storage.
- Keep cross-thread chunk loading and packet operations on server-thread-safe scheduling (FoliaLib dispatch as needed).
- Validate NBT format when loading chunks; skip malformed chunks with a warning.

## Implementation Plan (Phased)

1. Config and feature flag scaffolding.
2. Chunk interest tracker and movement-driven chunk window updates.
3. Baseline chunk capture and NBT export from Paper's chunk API.
4. Implement `.br`/`.brc` binary container writer/reader with section index and LZ4 blocks.
5. Storage backend integration: file backend stores container files, MySQL stores container blob.
6. Playback: chunk loading and packet dispatch to viewers.
7. Replay teardown: restore live chunks for all viewers.
8. Validation tools and tests.

## Testing Plan

- Unit tests for chunk interest set union/diff logic.
- Unit tests for deduplication across many tracked players.
- Integration test: chunk capture and `.brc` container output on recording stop.
- Playback test: baseline chunks + timeline block events reconstruct expected world state at arbitrary ticks.
- Regression test: replay end restores live chunks for all viewers.
- Stress test with synthetic 50-player movement across large areas.

## Open Questions

- What timeline block size should be default for LZ4 (for example 256 KB vs 1 MB)?
