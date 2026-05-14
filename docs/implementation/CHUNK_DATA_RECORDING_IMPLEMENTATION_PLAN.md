# Chunk Data Recording Implementation Plan

This document replaces the earlier block-state baseline implementation sequence with a packet-friendly chunk-section plan.

It assumes the architecture and constraints already captured in [CHUNK_DATA_RECORDING.md](../planning/CHUNK_DATA_RECORDING.md), plus the existing binary/archive contracts in [ARCHIVE_MANIFEST_SCHEMA.md](../ARCHIVE_MANIFEST_SCHEMA.md) and [BINARY_FORMAT_SPEC.md](../BINARY_FORMAT_SPEC.md).

## Goal

Implement optional chunk snapshot capture for `.br` replays so playback can transmit initial chunk state using chunk-oriented packets instead of one block update per changed block, while preserving the current binary replay contract, file/MySQL backend parity, and watcher-window on-demand chunk loading.

## Revised Core Model

The replay chunk system should follow this rule:

1. Record one packet-friendly chunk snapshot for the first observed state of each unique chunk.
2. Keep replay block changes in `replay.bin` as the authoritative mutation stream after the snapshot tick.
3. When a viewer enters a replay chunk at playback tick `t`:
   - send the recorded chunk snapshot as initial state
   - apply all tracked replay block mutations for that chunk up to tick `t`
4. When a viewer leaves replay range or replay stops:
   - resend live server chunk state
   - clear replay-side chunk override tracking

This keeps initial chunk transmission chunk-oriented, while replay-time world evolution still comes from timeline events.

## Non-Goals For The First Delivery Pass

- No replacement of `manifest.json` or `replay.bin` as required archive entries.
- No storage of exact raw network packet bytes as the archive contract.
- No chunk delta stream stored inside `chunks/` entries.
- No migration of existing chunk-enabled archives to the new packet-friendly payload shape.
- No persistent spill-to-disk cache of active replay chunk state during playback.
- No support for arbitrary cross-version packet payload reuse.

## Frozen Decisions

The implementation plan assumes these choices are fixed unless explicitly changed before coding starts:

- Replay artifact remains `.br`.
- Chunk data remains optional and additive via `chunks/` archive entries.
- Chunk snapshots remain grouped by region entry, not one archive entry per chunk.
- Finalized region entries keep a region-local index plus independently compressed per-chunk payloads.
- Recording still writes append-friendly temp region files first and finalizes into playback-optimized region entries at replay completion.
- The stored chunk payload is not exact outbound packet bytes. It is a packet-friendly internal chunk-section model that can be turned into outbound chunk packets for the current server target.
- Replay block changes remain sourced from timeline events in `replay.bin` and are replayed after initial chunk transmission.
- Initial chunk state should be transmitted with chunk-oriented packets, not `sendBlockChange` loops.
- Re-entering a replay chunk at playback tick `t` rebuilds visible state as snapshot plus indexed mutations up to `t`.
- Active in-memory replay chunk state is an optimization phase after correctness, not the first required delivery step.

## Why Not Store Exact Raw Packet Bytes

Storing exact raw packet bytes sounds attractive because it minimizes playback-time transformation, but it is a poor primary contract for BetterReplay.

Main issues:

- packet shapes are tightly coupled to server and protocol implementation details
- archive validation becomes harder because packet bytes are opaque
- future compatibility work becomes more expensive
- replay mutations still need to be applied after initial chunk send, so raw packet bytes alone do not solve arbitrary tick entry

The chosen model is therefore:

- store packet-friendly chunk-section data in a stable archive payload
- build outbound chunk packets from that internal model at playback time
- apply replay timeline block mutations after snapshot send

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

### Chunk snapshot capture service

Owns:

- extracting packet-friendly chunk-section state from the live server chunk
- producing a stable internal payload suitable for later packet emission

Suggested responsibilities:

- capture section block state containers
- capture biome containers if required for chunk-packet correctness
- capture block entities required for client-correct rendering
- define explicit policy for light and heightmap handling

### Temp region writer

Owns:

- append-friendly recording-time chunk persistence
- one-by-one snapshot appends
- region-local temporary metadata needed for finalization

### Archive finalizer

Owns:

- transforming temp region data into finalized `.brregion` entries
- inserting region entries into the `.br` archive
- producing chunk-related manifest fields

### Playback chunk loader and transmitter

Owns:

- reading `chunks/` archive entries
- region entry parsing
- per-chunk index lookup
- on-demand payload decompression
- building outbound chunk packets from internal snapshot payloads
- dispatching chunk packets only for the active watcher window

### Replay mutation index

Owns:

- mapping replay block mutations to affected chunks
- replaying per-chunk mutations up to an arbitrary playback tick after initial chunk send

### Replay teardown logic

Owns:

- tracking which replay chunks overrode live server state for a viewer
- resending live chunk data when replay stops or chunks leave replay range

## Recommended Delivery Strategy

Implement this as a vertical slice with format and packet assumptions frozen first, then recording capture, then playback transmission, then mutation reapplication.

Recommended order:

1. Freeze the packet-friendly chunk payload contract.
2. Add recording-time snapshot capture and temp persistence.
3. Add archive finalization and validation for the new chunk payload shape.
4. Add chunk-packet transmission for initial chunk state.
5. Add per-chunk replay mutation indexing and replay-after-send logic.
6. Add teardown restore and chunk re-entry correctness.
7. Add optional active in-memory chunk state caching if profiling still requires it.

This keeps the highest-risk pieces isolated early:

- snapshot payload contract
- what is required to build outbound chunk packets correctly
- replay mutation reapplication after initial chunk send
- chunk re-entry correctness at arbitrary playback ticks

## Phase 1: Freeze The Packet-Friendly Chunk Payload Contract

Deliverables:

- exact `BRCP` version `1` internal chunk snapshot payload contract
- explicit manifest additions for packet-friendly chunk archives
- explicit policy for biomes, block entities, light, and heightmaps
- confirmed reuse of the existing finalized `.brregion` and temp-region container layouts
- default soft-fail policy for unsupported or corrupt chunk sidecar data

Frozen decisions:

- payload magic/version: `BRCP` version `1`
- archive scope: one chunk payload family/version per archive
- section model: section-oriented payloads covering the full chunk vertical span from `minSectionY` for `sectionCount` sections
- per-section block data: local palette of canonical block-data strings plus packed indices for 4096 cells in Y-major, then Z-major, then X-major order
- per-section biome data: local palette of namespaced biome keys plus packed indices for 64 biome cells in Y-major, then Z-major, then X-major order
- block entities: stored as local position, namespaced type key, and binary NBT bytes
- heightmaps: not stored in the archive payload; derived from section block states during packet build if required
- light: not stored in the archive payload; replay reuses live-world light state and accepts lighting drift as a known Phase 1 limitation
- finalized `.brregion` layout: unchanged from the current 16-byte header plus 16-byte row container; only the compressed payload contents change
- temp-region append layout: unchanged from the current temp chunk record container; only the compressed payload contents change
- manifest additions: `chunkPayloadFormat` and `chunkPayloadVersion`, with legacy omission interpreted as `BRCS` version `1`
- default validation policy: warn and fall back to timeline-only playback when optional chunk metadata or chunk payload decoding is invalid

Implementation tasks:

- document the exact `BRCP` byte layout in the binary format spec
- document manifest compatibility defaults for legacy `BRCS` archives versus new `BRCP` archives
- document packet-builder responsibilities that remain outside the archive contract:
  - heightmap derivation
  - live-world light reuse
  - mapping palette strings to runtime packet values
- add golden fixtures for `BRCP` payload encode/decode, temp records, and finalized `.brregion` entries

Required decision before coding continues:

- outbound packet generation must target a stable current server/protocol surface
- the archive payload must remain internal-model based rather than exact raw packet bytes

Exit criteria:

- a developer can implement capture, finalization, packet building, and replay mutation reapplication without format guesses
- golden-file tests can be written for both temp records and finalized region entries

## Phase 2: Add Shared Abstractions And Config Integration

Deliverables:

- explicit chunk-capture config parsing
- isolated interfaces between capture, temp persistence, finalization, packet emission, and replay mutation indexing

Implementation tasks:

- keep typed accessors for:
  - `Recording.Chunk-Capture.Enabled`
  - `Recording.Chunk-Capture.Radius`
  - `Recording.Chunk-Capture.Capture-Interval-Ticks`
  - `Recording.Chunk-Capture.Max-Unique-Chunks-Per-Recording`
- introduce or update boundaries for:
  - chunk interest tracker
  - chunk snapshot capture service
  - temp region writer
  - chunk archive finalizer
  - replay chunk snapshot reader
  - replay chunk packet builder
  - replay chunk sender
  - replay chunk mutation index

Suggested interfaces:

- `ChunkInterestTracker`
- `ChunkSnapshotCaptureService`
- `ChunkTempRegionWriter`
- `ChunkArchiveFinalizer`
- `ReplayChunkArchiveReader`
- `ReplayChunkPacketBuilder`
- `ReplayChunkMutationIndex`

Exit criteria:

- chunk capture can be switched on through configuration without mixing responsibilities into unrelated replay code

## Phase 3: Recording-Time Discovery And Snapshot Capture

Deliverables:

- periodic chunk-interest recomputation
- deduplicated chunk snapshot discovery
- packet-friendly snapshot export for newly discovered chunks only

Implementation tasks:

- on each capture interval:
  - collect tracked player chunk positions
  - compute union within configured radius
  - compare with known captured chunk set
- for newly discovered chunks:
  - capture packet-friendly internal chunk-section snapshot
  - map chunk to region key
  - hand payload to temp region writer
- enforce max unique chunk cap and log if capture is truncated
- ensure repeated visits to the same chunk do not recapture snapshot data

Tests:

- single-player radius discovery
- multi-player overlapping radius deduplication
- far-apart players create independent region sets
- max unique chunk cap enforcement
- captured snapshot contains the fields required by the chosen packet builder contract

Exit criteria:

- active recordings can discover and export unique snapshots incrementally

## Phase 4: Temp Region Writers And Finalization

Deliverables:

- append-friendly temp region files during recording
- deterministic finalization into `.brregion` archive entries
- archive insertion of finalized region entries under `chunks/`

Implementation tasks:

- write temp region files under the recording temp workspace
- append one chunk snapshot payload at a time with enough metadata for finalization
- keep region and chunk metadata in memory or a side index
- on replay finalization:
  - read temp region data
  - build finalized region-local chunk index
  - build concatenated payload area
  - write `.brregion` entries into the final archive
- ensure clean-up of temp region files after successful finalization

Tests:

- multiple chunk appends into same region
- multiple regions in one recording
- deterministic finalization order
- replay archive contains expected `chunks/` entries after finalization

Exit criteria:

- chunk-enabled recordings produce stable `.br` archives with region-grouped packet-friendly entries

## Phase 5: Manifest And Validation Integration

Deliverables:

- chunk metadata written into manifest schema bump
- loader validation flow extended for packet-friendly chunk archives

Implementation tasks:

- write chunk-enabled manifest fields during finalization
- add payload-version or payload-kind metadata if needed to distinguish old and new chunk payload contracts
- extend archive loader validation order:
  - existing manifest validation
  - existing `replay.bin` checksum validation
  - chunk metadata and entry validation when `hasChunkData` is true
- implement soft-fail or hard-fail behavior according to the chosen policy
- define compatibility behavior for legacy chunk-enabled archives written with the old block-state payload shape

Tests:

- chunk-enabled manifest values present and correct
- chunk-disabled archives remain valid and unchanged
- corrupt or missing chunk metadata handled per policy
- old-format chunk-enabled archives are rejected or routed according to the explicit compatibility policy

Exit criteria:

- packet-friendly chunk-enabled `.br` archives can be distinguished and validated without disturbing current replay validation behavior

## Phase 6: Chunk Packet Transmission For Initial State

Deliverables:

- playback-side region lookup model
- watcher-window-driven chunk send path
- outbound chunk packet generation from packet-friendly snapshot payloads

Implementation tasks:

- build a lookup from archive `chunks/` entries by world and region coordinate
- when a watcher needs chunk `(x, z)`:
  - locate containing region entry
  - parse or cache the region-local index
  - locate exact chunk payload
  - decompress only that payload
  - decode internal snapshot payload
  - build and send outbound chunk packet(s)
- do not use `sendBlockChange` loops for initial chunk state
- keep region and chunk cache scope bounded for playback sessions
- do not load all region entries at replay start

Tests:

- watcher receives nearby chunks only
- distant recorded areas remain unopened until watcher moves there
- same region reused without reparsing on repeated nearby access
- multi-viewer sessions do not corrupt each other’s chunk state
- initial chunk state no longer requires one per-block send loop

Exit criteria:

- playback can transmit initial replay chunk state with chunk-oriented packets instead of block-by-block updates

## Phase 7: Replay Mutation Index And Reapply-After-Send Logic

Deliverables:

- per-chunk replay mutation index built from `replay.bin`
- correct chunk entry behavior at arbitrary playback tick `t`
- chunk re-entry correctness after the viewer moves out of range and back in range

Implementation tasks:

- build an index of replay block mutations by chunk coordinate and tick
- when a chunk is first sent or re-sent:
  - locate all replay mutations for that chunk up to current playback tick
  - apply them in tick order after initial chunk packet transmission
- ensure this logic works during:
  - normal forward playback
  - paused state while moving the viewer
  - stepping forward and backward
  - replay restarts and seeks if those behaviors are added later

Tests:

- entering a replay chunk at tick zero sends snapshot with no later mutations
- entering a replay chunk mid-replay sends snapshot then replays earlier mutations correctly
- leaving and re-entering range reconstructs the same visible chunk state
- block mutation order remains deterministic

Exit criteria:

- arbitrary chunk entry during replay reconstructs the correct visible chunk state as snapshot plus replay mutations

## Phase 8: Replay Teardown And Live-World Restore

Deliverables:

- tracked set of overridden chunks per replay viewer
- replay stop path that restores live chunk data
- chunk unload path that restores live data when replay chunks leave the active viewer window

Implementation tasks:

- track which chunks have been replaced by replay chunk packets for each viewer
- on chunk unload or replay end:
  - identify affected chunk coordinates
  - resend live server chunk data for those chunks
  - clear replay chunk override state
- ensure block mutation overlays do not outlive their owning replay chunk state

Tests:

- replay stop restores live chunks after local replay viewing
- moving out of replay range restores live chunks correctly
- far-area replay chunks are restored correctly
- state clears correctly across repeated replay sessions

Exit criteria:

- replay chunk overlays do not persist after replay stop or chunk unload

## Phase 9: Optional Active In-Memory Replay Chunk State

This phase is an optimization phase, not a correctness prerequisite.

Deliverables:

- bounded in-memory replay chunk state cache for currently relevant chunks
- reduced cost for repeated chunk re-entry during the same replay session

Implementation tasks:

- maintain active replay chunk state for chunks in or near the viewer window
- apply replay mutations into active chunk state as playback advances
- decide eviction strategy when chunks leave the working set
- avoid persistent spill-to-disk unless profiling proves it is necessary

Tests:

- repeated enter and leave cycles reuse active chunk state correctly
- active cache eviction does not corrupt replay state
- memory usage remains bounded under wide viewer radius movement

Exit criteria:

- larger viewer chunk load distances are supportable without repeatedly rebuilding every nearby chunk from snapshot plus mutation history

## Phase 10: Hardening, Regression Coverage, And Operational Limits

Deliverables:

- corruption handling coverage
- stress coverage for many players, many regions, and larger viewer radii
- guardrails for disk growth, packet-build cost, and replay mutation reapply cost

Implementation tasks:

- add validation and regression tests for:
  - malformed `.brregion` header
  - malformed region-local chunk index
  - invalid payload offsets and lengths
  - corrupt compressed chunk payloads
  - invalid packet-friendly snapshot payload internals
- add stress tests for:
  - many tracked players across distant areas
  - many region entries in one archive
  - repeated watcher movement across region boundaries
  - larger viewer load radii
- verify file and MySQL backend parity for packet-friendly chunk-enabled `.br` archives

Exit criteria:

- the feature is stable enough for real-world recordings with predictable failure behavior and acceptable initial chunk transmission cost

## Recommended Initial Coding Order

If implementation starts immediately, the safest short sequence is:

1. Freeze the packet-friendly chunk snapshot payload and region-entry contract.
2. Build capture and finalization golden tests before integrating with recording sessions.
3. Integrate recording-time discovery and snapshot capture.
4. Add archive validation for the new chunk payload shape.
5. Build chunk-packet transmission for initial state.
6. Add per-chunk replay mutation indexing and reapply-after-send logic.
7. Finish with teardown restore, chunk re-entry correctness, and only then profile whether active in-memory replay chunk state is necessary.
