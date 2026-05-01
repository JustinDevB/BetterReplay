# Chunk Data Recording Design

This document proposes a replay extension that captures world chunk state alongside timeline events.
The goal is to make replays portable across servers and improve visual fidelity by reproducing the recorded world state, not just entity movement.

## Problem Statement

Current recordings primarily capture entity and interaction events. Playback correctness depends on the live world at replay time.

That creates gaps:

- A replay played on another server may have different terrain, blocks, or builds.
- A replay played later on the same server may no longer match original world state.
- Fast-moving players can cross many chunks, making world divergence more visible.

## Current Format Baseline (Already Implemented)

The chunk-data plan must build on the implemented binary/archive stack, not replace it.

Implemented today:

- Replay artifact is a `.br` archive.
- Archive required entries are `manifest.json` and `replay.bin`.
- `manifest.json` follows [ARCHIVE_MANIFEST_SCHEMA.md](../ARCHIVE_MANIFEST_SCHEMA.md).
- `replay.bin` follows [BINARY_FORMAT_SPEC.md](../BINARY_FORMAT_SPEC.md).
- `replay.bin` is currently stored as a single LZ4 payload that is fully decompressed into memory on load.
- Archive-level integrity is validated via manifest checksum (`payloadChecksum` + `payloadChecksumAlgorithm`).

Reserved but not required in v1:

- `chunks/` archive prefix
- `meta/` archive prefix

This plan uses those reserved prefixes for region-grouped chunk storage rather than one archive entry per chunk.

This document describes how chunk capture should extend that baseline safely.

For the concrete delivery sequence, see [CHUNK_DATA_RECORDING_IMPLEMENTATION_PLAN.md](../implementation/CHUNK_DATA_RECORDING_IMPLEMENTATION_PLAN.md).

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
- Replacing the current required archive entries (`manifest.json` + `replay.bin`) in the first chunk-enabled phase.

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
  - `false`: write standard `.br` archive with `manifest.json` + `replay.bin` only.
  - `true`: write `.br` archive that also includes region-grouped `chunks/` entries.
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

Captured chunk baselines are grouped by Minecraft region coordinate (`regionX`, `regionZ`, 32 x 32 chunks) rather than stored as one standalone archive file per chunk.

Inside each finalized region payload:

- each chunk remains independently addressable
- each chunk payload is compressed independently
- a region-local index maps chunk coordinates to payload offset, compressed length, uncompressed length, and codec

All world modifications after baseline are reconstructed from timeline events (`BlockPlace`, `BlockBreak`, `BlockBreakStage`, and related events) in tick order.

### 3. Multi-player scaling

Use one shared per-recording chunk registry:

- Deduplicate chunk baselines across players.
- Player movement updates references, not payload duplication.
- Enforce maximum unique chunk cap.

## Storage Plan

Adopt one canonical `.br` archive format with additive chunk support. Both file and MySQL backends store the same archive bytes.

The chunk feature extends the existing archive contract instead of introducing a second artifact type.

### File extensions

- Keep `.br` as the canonical replay extension.
- Presence of chunk data is detected by archive entries and manifest metadata, not by extension.

### Container layout

Required entries (unchanged):

1. `manifest.json`
2. `replay.bin`

Chunk-enabled additions:

3. `chunks/` entry set (optional; one finalized entry per region that contains captured chunks)
4. `meta/` chunk index hints (optional)

This preserves backward compatibility with the current loader flow and schema.

Suggested chunk entry naming:

- `chunks/<world>/r.<regionX>.<regionZ>.brregion`

Example:

- `chunks/world/r.0.0.brregion`
- `chunks/world_nether/r.0.-1.brregion`

### Region entry structure

Each finalized region entry should contain:

1. region header
2. region-local chunk index
3. concatenated chunk payload area

Each index row stores at minimum:

- local chunk X within region (`0-31`)
- local chunk Z within region (`0-31`)
- payload offset
- compressed length
- uncompressed length
- codec identifier

This keeps the archive entry count low while still allowing a single requested chunk to be loaded and decompressed without decoding unrelated chunks in the same region.

### Recording-time temp layout

During recording, chunk data should not be written directly into the final playback-optimized region entry format.

Instead:

- use append-friendly temp region files under the recording temp workspace
- append newly discovered chunk baselines one at a time
- track region/chunk metadata in memory or a side index during recording
- finalize temp region files into clean `.brregion` entries when the replay archive is built

This matches the existing timeline design philosophy: append-friendly writes during recording, playback-optimized structure at finalize time.

### Compression

- Keep `replay.bin` compression exactly as defined in the current spec (single LZ4 payload in v1).
- Chunk region entries should be independently loadable archive entries.
- Inside a region entry, each chunk payload should be compressed independently.
- LZ4 does not provide chunk-level random access automatically inside one large compressed region blob; the per-chunk index and per-chunk compression boundary provide that behavior.
- Chunk loading remains on-demand by watcher window; do not load all chunk entries at replay start.

### Logical internal view

```
MyReplay.br
  manifest.json
  replay.bin
  chunks/... (optional region entries)
  meta/...   (optional)
```

This is the archive entry model. The binary timeline internals remain defined by `replay.bin` format spec.

### Backend mapping

File backend:

- One file per replay: `name.br`.
- Delete operation is a single file remove.

MySQL backend:

- One row per replay with one container blob column.
- Delete operation is a single row remove.

No separate chunk table is required when MySQL stores the full `.br` bytes as one blob.

### Manifest additions for chunk-enabled replays

The current manifest schema does not yet require chunk metadata. For chunk support, add explicit fields in a schema bump.

Proposed additive fields:

- `hasChunkData` (boolean)
- `chunkRegionEntryCount` (integer)
- `chunkEntryCount` (integer)
- `chunkCoordinateHash` (string, optional integrity/debug helper)

Validation expectations for chunk-enabled archives:

1. Standard manifest + `replay.bin` validation still runs first.
2. If `hasChunkData` is true, loader validates region entry presence and basic entry integrity.
3. Missing/corrupt chunk entries degrade to entity-only playback (or hard-fail if strict mode is enabled).

## Playback Behavior

### Replay start

When replay data includes chunk payloads:

1. Perform normal `.br` manifest and `replay.bin` validation/load.
2. Build an in-memory region and chunk lookup from `chunks/` (and `meta/` if present).
3. For the replay viewer(s), send only chunks inside the current watcher window.
4. Start/continue timeline playback while loading additional chunks on demand.

### During replay

- Apply block changes in tick order as they appear in the timeline (`BlockPlace`, `BlockBreak`, `BlockBreakStage` events).
- The baseline chunks plus timeline edits reconstruct the full world state at any playback tick.

### Chunk availability vs active loading

Captured chunks in a chunk-enabled `.br` recording are treated as **available data**, not always-loaded data.

- If multiple recorded players are far apart (for example 1000+ blocks), chunks for all areas can exist in the recording.
- During playback, the watcher only receives chunks inside their current playback window.
- Chunks outside that window remain indexed and available, but their containing region entries are not opened and their per-chunk payloads are not decompressed yet.
- If the watcher moves to a distant recorded area later, those chunks are then loaded on demand and sent.

In short: capture scope determines what can be shown; watcher position determines what is loaded now.

### Replay end

When replay stops, for every chunk overridden by replay playback:

1. Mark chunk coordinates as dirty in replay session state.
2. Send the real server chunk data back to affected viewers.
3. Clear replay chunk overrides and viewer tracking state.

This guarantees viewers return to live world state after replay completion.

## Failure and Safety Considerations

- If manifest or `replay.bin` validation fails, replay load is a hard failure (existing behavior).
- If optional region entries or indexed chunk payloads are missing/corrupt, warn and fall back to entity-only playback for affected areas.
- Enforce maximum chunk count and total directory size limits to prevent runaway storage.
- Keep cross-thread chunk loading and packet operations on server-thread-safe scheduling (FoliaLib dispatch as needed).
- Validate NBT format when loading chunks; skip malformed chunks with a warning.

## Implementation Plan (Phased)

1. Config and feature flag scaffolding.
2. Chunk interest tracker and movement-driven chunk window updates.
3. Baseline chunk capture and NBT export from Paper's chunk API.
4. Recording-time temp region writer: append newly discovered chunk baselines into temp region files.
5. Finalization step: build indexed `.brregion` archive entries from temp region files.
6. Manifest/schema update: add chunk presence/count fields and validation rules.
7. Storage backend integration: file backend stores `.br`; MySQL stores `.br` blob.
8. Playback: region lookup, per-chunk index lookup, on-demand chunk decode, and packet dispatch by watcher window.
9. Replay teardown: restore live chunks for all viewers.
10. Validation tools and tests.

## Testing Plan

- Unit tests for chunk interest set union/diff logic.
- Unit tests for deduplication across many tracked players.
- Integration test: chunk capture and region-grouped chunk-enabled `.br` archive output on recording stop.
- Integration test: temp region append-log finalizes into stable indexed region entries.
- Playback test: baseline chunks + timeline block events reconstruct expected world state at arbitrary ticks.
- Validation test: manifest + `replay.bin` hard-fail remains unchanged when chunk feature is enabled.
- Validation test: corrupt/missing region entry or indexed chunk payload degrades chunk overlay behavior per policy.
- Regression test: replay end restores live chunks for all viewers.
- Stress test with synthetic 50-player movement across large areas.

## Open Questions

- Should replay payload evolve from single-frame LZ4 to block-indexed LZ4 in a future format version?
- Should chunk entry corruption be a soft-fail (recommended) or strict hard-fail policy by default?
