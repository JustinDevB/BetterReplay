# Chunk Data Recording Implementation Plan

This document converts the chunk-data recording design into an implementation sequence for BetterReplay.

It assumes the architecture and constraints already captured in [CHUNK_DATA_RECORDING.md](../planning/CHUNK_DATA_RECORDING.md), plus the existing binary/archive contracts in [ARCHIVE_MANIFEST_SCHEMA.md](../ARCHIVE_MANIFEST_SCHEMA.md) and [BINARY_FORMAT_SPEC.md](../BINARY_FORMAT_SPEC.md).

## Goal

Implement optional chunk baseline capture for `.br` replays so playback can reconstruct recorded world state across servers while preserving the current binary replay contract, file/MySQL backend parity, and watcher-window on-demand chunk loading.

## Non-Goals for the First Delivery Pass

- No replacement of `manifest.json` or `replay.bin` as required archive entries.
- No chunk delta stream inside the chunk storage format.
- No migration of existing `.br` archives to chunk-enabled archives.
- No spatial query tooling or debug export optimization.
- No change to the current replay payload loading model for `replay.bin`.

## Frozen Assumptions

The implementation plan assumes these choices are fixed unless explicitly changed before coding starts:

- Replay artifact remains `.br`.
- Chunk data is optional and additive via `chunks/` archive entries.
- Chunk baselines are grouped into region entries, not one archive entry per chunk.
- Each finalized region entry contains individually compressed chunk payloads and a per-region chunk index.
- Recording writes append-friendly temp region data first and finalizes into playback-optimized region entries at replay completion.
- Replay playback loads/sends chunks by watcher window, not by full recorded chunk set.
- Replay block changes remain sourced from timeline events in `replay.bin`.

## Recommended Delivery Strategy

Implement this as a vertical slice with hard format decisions first, then write-side capture, then playback.

Recommended order:

1. Freeze chunk-region file contracts and manifest additions.
2. Add recording-time discovery and baseline capture.
3. Add temp region writers and archive finalization.
4. Add playback lookup and on-demand chunk loading.
5. Add replay teardown restore behavior.
6. Harden validation, corruption handling, and regression coverage.

This keeps the highest-risk pieces isolated early:

- region file structure
- append-friendly temp write format
- archive finalization correctness
- chunk loading behavior during playback

## Ownership Boundaries

### Recording session and capture orchestration

Owns:

- chunk interest tracking
- deduplication of discovered chunks
- scheduling capture interval recomputation
- handing newly discovered chunks to temp region writers

Suggested responsibilities:

- maintain `(worldId, chunkX, chunkZ)` registry
- maintain region grouping metadata
- enforce `Max-Unique-Chunks-Per-Recording`

### Temp region writer

Owns:

- append-friendly recording-time chunk persistence
- one-by-one baseline chunk appends
- region-local temporary metadata needed for finalization

Suggested responsibilities:

- append chunk payload records to temp region files
- record local chunk coordinates and payload metadata
- expose a stable read model for finalization

### Archive finalizer

Owns:

- transforming temp region data into finalized `.brregion` entries
- inserting region entries into the `.br` archive
- producing chunk-related manifest fields

### Playback chunk loader

Owns:

- reading `chunks/` archive entries
- region entry parsing
- per-chunk index lookup
- on-demand payload decompression
- dispatching chunk packets only for the active watcher window

### Replay teardown logic

Owns:

- tracking which replay chunks overrode live server state for a viewer
- resending live chunk data when replay stops

## Phase 1: Freeze the Chunk Format Contract

Deliverables:

- Exact `.brregion` binary layout
- Exact temp region append-log layout
- Manifest schema additions for chunk-enabled archives
- Corruption/failure policy for region entries and chunk payloads

Implementation tasks:

- Define the finalized `.brregion` layout byte-for-byte:
  - region header
  - region-local chunk index
  - concatenated payload area
- Define each index row exactly:
  - local chunk X
  - local chunk Z
  - payload offset
  - compressed length
  - uncompressed length
  - codec identifier
- Define the temp region append record layout used during recording.
- Define chunk payload codec rules for v1.
- Freeze chunk entry naming rules under `chunks/<world>/r.<regionX>.<regionZ>.brregion`.
- Freeze manifest additions such as:
  - `hasChunkData`
  - `chunkRegionEntryCount`
  - `chunkEntryCount`
  - `chunkCoordinateHash` if retained
- Decide default failure policy for corrupt region entries or chunk payloads.

Frozen v1 decisions:

- Finalized region entries use magic `BRRG`, version `1`, a 16-byte header, and 16-byte fixed-width index rows.
- Region index rows are ordered lexicographically by `(localChunkX, localChunkZ)`.
- Region `payloadOffset` values are relative to the start of the region payload area, not the file start.
- Temp region files use magic `BRTC`, version `1`, an 8-byte file header, and 16-byte record headers followed by payload bytes.
- Temp append records store CRC32C of the compressed payload bytes for tail corruption detection.
- v1 chunk payload codec ID `0x01` is `LZ4_FRAME`.
- Archive entry names use `chunks/<worldSegment>/r.<regionX>.<regionZ>.brregion`, where `worldSegment` percent-encodes UTF-8 bytes outside `[A-Za-z0-9._-]` using uppercase `%HH` escapes.
- Manifest chunk metadata remains additive on top of `formatVersion = 1`; omitted fields default to `hasChunkData = false`, zero counts, and no coordinate hash.
- Default corruption handling is soft-fail to entity-only playback for affected chunk overlays; strict hard-fail remains opt-in for later phases.

Exit criteria:

- A developer can implement the temp writer, finalizer, and playback reader without format guesses.
- Golden-file tests can be written for finalized region entries.

## Phase 2: Add Shared Abstractions and Config Integration

Deliverables:

- explicit chunk-capture config parsing
- isolated interfaces between capture, temp persistence, finalization, and playback lookup

Implementation tasks:

- Add typed accessors for:
  - `Recording.Chunk-Capture.Enabled`
  - `Recording.Chunk-Capture.Radius`
  - `Recording.Chunk-Capture.Capture-Interval-Ticks`
  - `Recording.Chunk-Capture.Max-Unique-Chunks-Per-Recording`
- Introduce or identify boundaries for:
  - chunk interest tracker
  - chunk baseline capture service
  - temp region writer
  - chunk archive finalizer
  - playback chunk loader
- Ensure the existing binary replay writer/finalizer can accept optional chunk artifacts without changing the `replay.bin` contract.

Suggested interfaces:

- `ChunkInterestTracker`
- `ChunkBaselineCaptureService`
- `ChunkTempRegionWriter`
- `ChunkArchiveFinalizer`
- `ReplayChunkArchiveReader`

Exit criteria:

- Chunk capture can be switched on through configuration without mixing responsibilities into unrelated replay code.

## Phase 3: Recording-Time Discovery and Baseline Capture

Deliverables:

- periodic chunk-interest recomputation
- deduplicated baseline chunk discovery
- NBT export for newly discovered chunks only

Implementation tasks:

- On each capture interval:
  - collect tracked player chunk positions
  - compute union within configured radius
  - compare with known captured chunk set
- For newly discovered chunks:
  - export baseline chunk NBT
  - map chunk to region key
  - hand payload to temp region writer
- Enforce max unique chunk cap and log if capture is truncated.
- Ensure repeated visits to the same chunk do not recapture baseline data.

Tests:

- single-player radius discovery
- multi-player overlapping radius deduplication
- far-apart players create independent region sets
- max unique chunk cap enforcement

Exit criteria:

- Active recordings can discover and export unique baseline chunks incrementally.

## Phase 4: Temp Region Writers and Finalization

Deliverables:

- append-friendly temp region files during recording
- deterministic finalization into `.brregion` archive entries
- archive insertion of finalized region entries under `chunks/`

Implementation tasks:

- Write temp region files under the recording temp workspace.
- Append one chunk payload at a time with enough metadata for finalization.
- Keep region/chunk metadata in memory or a side index.
- On replay finalization:
  - read temp region data
  - build finalized region-local chunk index
  - build concatenated payload area
  - write `.brregion` entries into the final archive
- Ensure clean-up of temp region files after successful finalization.

Tests:

- multiple chunk appends into same region
- multiple regions in one recording
- deterministic finalization order
- replay archive contains expected `chunks/` entries after finalization

Exit criteria:

- Chunk-enabled recordings produce stable `.br` archives with region-grouped entries.

## Phase 5: Manifest and Validation Integration

Deliverables:

- chunk metadata written into manifest schema bump
- loader validation flow extended for chunk-enabled archives

Implementation tasks:

- Write chunk-enabled manifest fields during finalization.
- Extend archive loader validation order:
  - existing manifest validation
  - existing `replay.bin` checksum validation
  - chunk metadata/entry validation when `hasChunkData` is true
- Implement soft-fail or hard-fail behavior according to the chosen policy.

Tests:

- chunk-enabled manifest values present and correct
- chunk-disabled archives remain valid and unchanged
- corrupt or missing chunk metadata handled per policy

Exit criteria:

- Chunk-enabled `.br` archives can be distinguished and validated without disturbing current replay validation behavior.

## Phase 6: Playback Region Lookup and On-Demand Chunk Loading

Deliverables:

- playback-side region lookup model
- watcher-window-driven chunk send path
- per-chunk decode from finalized region entries

Implementation tasks:

- Build a lookup from archive `chunks/` entries by world and region coordinate.
- When a watcher needs chunk `(x, z)`:
  - locate containing region entry
  - parse or cache the region-local index
  - locate exact chunk payload
  - decompress only that payload
  - send resulting chunk data to the watcher
- Keep region and/or chunk cache scope bounded for playback sessions.
- Do not load all region entries at replay start.

Tests:

- watcher receives nearby chunks only
- distant recorded areas remain unopened until watcher moves there
- same region reused without reparsing on repeated nearby access
- multi-viewer sessions do not corrupt each other’s chunk state

Exit criteria:

- Playback can reconstruct recorded world state lazily based on watcher position.

## Phase 7: Replay Teardown and Live-World Restore

Deliverables:

- tracked set of overridden chunks per replay viewer
- replay stop path that restores live chunk data

Implementation tasks:

- Track which chunks have been replaced by replay data for each viewer.
- On replay end:
  - identify affected chunk coordinates
  - resend live server chunk data for those chunks
  - clear replay chunk override state

Tests:

- replay stop restores live chunks after local replay viewing
- far-area replay chunks are restored correctly
- state clears correctly across repeated replay sessions

Exit criteria:

- Replay chunk overlays do not persist after replay stop.

## Phase 8: Hardening, Regression Coverage, and Operational Limits

Deliverables:

- corruption handling coverage
- stress coverage for many players and many regions
- guardrails for disk growth and decode cost

Implementation tasks:

- Add validation and regression tests for:
  - malformed `.brregion` header
  - malformed region-local chunk index
  - invalid payload offsets/lengths
  - corrupt compressed chunk payloads
- Add stress tests for:
  - many tracked players across distant areas
  - many region entries in one archive
  - repeated watcher movement across region boundaries
- Verify file and MySQL backend parity for chunk-enabled `.br` archives.

Exit criteria:

- The feature is stable enough for real-world recordings with predictable failure behavior.

## Recommended Initial Coding Order

If implementation starts immediately, the safest short sequence is:

1. Freeze `.brregion` and temp-region formats.
2. Build temp region writer tests before integrating with recording sessions.
3. Integrate capture/discovery into recording sessions.
4. Build archive finalization for `chunks/` entries.
5. Add loader/validation changes.
6. Add playback region lookup and on-demand decode.
7. Finish with replay teardown restore behavior and stress tests.