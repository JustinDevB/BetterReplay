# Chunk Data Recording Design

This document proposes a replay extension that captures world chunk state alongside timeline events.
The goal is to make replays portable across servers and improve visual fidelity by reproducing the recorded world state, not just entity movement.

The design has now shifted away from block-by-block replay chunk bootstrap. The intended model is:

- record a packet-friendly internal chunk snapshot when a chunk is first discovered
- send initial replay chunk state with chunk-oriented packets
- apply replay timeline block mutations after the chunk snapshot is sent

## Problem Statement

Current recordings primarily capture entity and interaction events. Playback correctness depends on the live world at replay time.

That creates gaps:

- A replay played on another server may have different terrain, blocks, or builds.
- A replay played later on the same server may no longer match original world state.
- Fast-moving players can cross many chunks, making world divergence more visible.
- Sending initial replay chunk state as one block update per differing block does not scale well enough for larger replay view distances.

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
- Transmit initial replay chunk state with chunk-oriented packets instead of one block update per block.
- Restore real server chunk data to viewers when replay ends or replay chunks leave range.

## Non-Goals

- Replacing the server's world files on disk.
- Supporting random third-party chunk formats in v1.
- Storing chunk delta streams in the chunk section.
- Replacing the current required archive entries (`manifest.json` + `replay.bin`) in the first chunk-enabled phase.
- Using exact raw outbound packet bytes as the archive contract.
- Persistent spill-to-disk active replay chunk caching in the first correctness-focused phase.

## Design Principles

### 1. Store what is easiest to retransmit, not what is easiest to inspect by hand

The stored chunk payload should be a packet-friendly internal chunk-section model that can be turned into outbound chunk packets efficiently at playback time.

It should not be:

- a block-by-block viewer overlay format
- a stream of chunk deltas inside `chunks/`
- an exact dump of raw outbound packet bytes that is tightly bound to one protocol implementation

### 2. Snapshot plus replay mutations is the authoritative reconstruction model

For each unique chunk:

- capture one first-observation snapshot payload
- keep later world evolution in `replay.bin`
- when a viewer enters that chunk at playback tick `t`, send the snapshot first and then replay all indexed block mutations for that chunk up to tick `t`

This means replay chunk correctness still comes from timeline events after the initial chunk send.

### 3. Chunk entry at arbitrary tick must be deterministic

If a viewer moves into a replay area after playback has already advanced, the visible chunk must be reconstructed as:

1. recorded chunk snapshot
2. replay mutations affecting that chunk from snapshot tick up to current playback tick

The same logic must work for first load and re-entry after moving out of range.

### 4. Active in-memory replay chunk state is an optimization, not the core contract

The base design should be correct without relying on a persistent mutable in-memory copy of every replay chunk. That optimization can be added later if profiling shows chunk re-entry rebuild cost is still too high.

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
4. Capture a snapshot for newly discovered chunks.

### 2. First-observation chunk snapshot capture

Capture chunk data once per unique `(worldId, chunkX, chunkZ)`:

- store one packet-friendly snapshot payload for first observation only
- if chunk leaves and re-enters range during recording, reuse the existing snapshot
- do not write chunk deltas into the chunk section

Captured chunk snapshots are grouped by Minecraft region coordinate (`regionX`, `regionZ`, 32 x 32 chunks) rather than stored as one standalone archive file per chunk.

Inside each finalized region payload:

- each chunk remains independently addressable
- each chunk payload is compressed independently
- a region-local index maps chunk coordinates to payload offset, compressed length, uncompressed length, and codec

All world modifications after snapshot capture are reconstructed from timeline events (`BlockPlace`, `BlockBreak`, `BlockBreakStage`, and related events) in tick order.

### 3. Multi-player scaling

Use one shared per-recording chunk registry:

- deduplicate chunk snapshots across players
- player movement updates references, not payload duplication
- enforce maximum unique chunk cap

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
- payload kind or version if needed to distinguish snapshot contract revisions

This keeps the archive entry count low while still allowing a single requested chunk to be loaded and decompressed without decoding unrelated chunks in the same region.

### Chunk payload contract

Each stored chunk payload should use the frozen `BRCP` version `1` packet-friendly internal model.

`BRCP` v1 decisions:

- one archive uses one payload family/version for all stored chunks
- the payload is section-oriented, not one flat whole-chunk block array and not raw packet bytes
- each chunk stores all vertical sections from `minSectionY` through `minSectionY + sectionCount - 1`
- each section stores:
  - a local block-state palette of canonical block-data strings
  - packed block-state indices for 4096 cells in Y-major, then Z-major, then X-major order
  - a local biome palette of namespaced biome keys
  - packed biome indices for 64 biome cells in Y-major, then Z-major, then X-major order
- trailing block entity records store local position, namespaced type key, and binary NBT bytes
- heightmaps are not persisted; the packet builder derives them from section block states when required
- light data is not persisted; replay playback reuses live-world light data and accepts lighting drift as a Phase 1 limitation

This keeps the payload packet-friendly without freezing ephemeral raw packet bytes into the archive contract.

### Recording-time temp layout

During recording, chunk data should not be written directly into the final playback-optimized region entry format.

Instead:

- use append-friendly temp region files under the recording temp workspace
- append newly discovered chunk snapshots one at a time
- track region and chunk metadata in memory or a side index during recording
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

Frozen additive fields:

- `hasChunkData` (boolean)
- `chunkRegionEntryCount` (integer)
- `chunkEntryCount` (integer)
- `chunkCoordinateHash` (string, optional integrity/debug helper)
- `chunkPayloadFormat` (string, defaults to `BRCS` for legacy archives and must be `BRCP` for packet-friendly archives)
- `chunkPayloadVersion` (integer, `1` for both `BRCS` and `BRCP` today)

Validation expectations for chunk-enabled archives:

1. Standard manifest + `replay.bin` validation still runs first.
2. If `hasChunkData` is true, loader validates chunk metadata, including payload format/version support.
3. Missing, corrupt, or unsupported chunk entries disable chunk sidecar usage and degrade to timeline-only playback unless strict mode is enabled.

## Playback Behavior

### Replay start

When replay data includes chunk payloads:

1. Perform normal `.br` manifest and `replay.bin` validation and load.
2. Build an in-memory region and chunk lookup from `chunks/` (and `meta/` if present).
3. For the replay viewer(s), send only chunks inside the current watcher window.
4. Start or continue timeline playback while loading additional chunks on demand.

### Initial chunk entry

When a viewer needs chunk `(x, z)`:

1. Locate the chunk payload from the region index.
2. Decompress only that chunk payload.
3. Build outbound chunk packet(s) from the stored internal snapshot payload.
4. Send the initial chunk state with chunk-oriented packets.
5. Apply indexed replay block mutations for that chunk up to current playback tick.

This replaces the older block-by-block bootstrap idea.

### During replay

- Replay block changes remain the authoritative world mutation stream after the initial chunk snapshot is sent.
- The visible state of a replay chunk at tick `t` is reconstructed as snapshot plus replay mutations up to `t`.
- If the viewer moves out of range and later back into range, the same reconstruction rule must be used again.

### Chunk availability vs active loading

Captured chunks in a chunk-enabled `.br` recording are treated as **available data**, not always-loaded data.

- If multiple recorded players are far apart, chunks for all areas can exist in the recording.
- During playback, the watcher only receives chunks inside their current playback window.
- Chunks outside that window remain indexed and available, but their containing region entries are not opened and their per-chunk payloads are not decompressed yet.
- If the watcher moves to a distant recorded area later, those chunks are then loaded on demand, sent, and replay mutations for those chunks are applied up to current playback tick.

In short: capture scope determines what can be shown; watcher position determines what is loaded now.

### Active in-memory replay chunk state

Maintaining mutable in-memory replay chunk state around the viewer is a valid future optimization, especially for larger replay view distances.

However, it is not required for the first correct implementation. The base contract is still snapshot plus mutation reapply on chunk entry.

### Replay end

When replay stops, for every chunk overridden by replay playback:

1. Mark chunk coordinates as dirty in replay session state.
2. Send the real server chunk data back to affected viewers.
3. Clear replay chunk overrides and viewer tracking state.

This guarantees viewers return to live world state after replay completion.

## Failure and Safety Considerations

- If manifest or `replay.bin` validation fails, replay load is a hard failure (existing behavior).
- If optional region entries or indexed chunk payloads are missing or corrupt, warn and fall back to entity-only playback for affected areas.
- Enforce maximum chunk count and total directory size limits to prevent runaway storage.
- Keep cross-thread chunk loading and packet operations on server-thread-safe scheduling (FoliaLib dispatch as needed).
- Validate packet-friendly chunk payload structure when loading chunks; skip malformed chunks with a warning according to the chosen corruption policy.

## Implementation Plan (Phased)

1. Config and feature flag scaffolding.
2. Chunk interest tracker and movement-driven chunk window updates.
3. First-observation chunk snapshot capture from the live server chunk into the packet-friendly internal model.
4. Recording-time temp region writer: append newly discovered chunk snapshots into temp region files.
5. Finalization step: build indexed `.brregion` archive entries from temp region files.
6. Manifest and schema update: add chunk presence, count, `chunkPayloadFormat`, and `chunkPayloadVersion` fields plus validation rules.
7. Storage backend integration: file backend stores `.br`; MySQL stores `.br` blob.
8. Playback: region lookup, per-chunk index lookup, on-demand chunk decode, packet build, and chunk-oriented packet dispatch by watcher window.
9. Replay mutation index: apply chunk-local replay block changes after the chunk snapshot is sent.
10. Replay teardown: restore live chunks for all viewers.
11. Optional optimization: active in-memory replay chunk state for the currently relevant working set.
12. Validation tools and tests.

## Testing Plan

- Unit tests for chunk interest set union and diff logic.
- Unit tests for deduplication across many tracked players.
- Integration test: chunk capture and region-grouped chunk-enabled `.br` archive output on recording stop.
- Integration test: temp region append-log finalizes into stable indexed region entries.
- Playback test: sending a chunk snapshot and then replaying indexed block events reconstructs expected world state at arbitrary ticks.
- Playback test: leaving and re-entering replay range reconstructs the same visible chunk state.
- Validation test: manifest + `replay.bin` hard-fail remains unchanged when chunk feature is enabled.
- Validation test: corrupt or missing region entry or indexed chunk payload degrades chunk overlay behavior per policy.
- Regression test: replay end restores live chunks for all viewers.
- Stress test with synthetic multi-player movement across large areas and wider replay view distances.

## Open Questions

- Should active in-memory replay chunk state be introduced immediately after correctness, or only after profiling wider replay view distances?
- Should strict hard-fail chunk validation be exposed as a user config once the default soft-fail policy is stable?
