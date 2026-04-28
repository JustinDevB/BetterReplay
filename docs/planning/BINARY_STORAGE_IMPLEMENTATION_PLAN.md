# Binary Storage Implementation Plan

This document converts the chosen binary storage design into an implementation sequence for BetterReplay.

It assumes the decisions already captured in [STORAGE_FORMAT_COMPARISON.md](f:\Minecraft\Forks\BetterReplay\docs\planning\STORAGE_FORMAT_COMPARISON.md) and [TEMP_BINARY_STORAGE_OPEN_QUESTIONS.md](f:\Minecraft\Forks\BetterReplay\docs\planning\TEMP_BINARY_STORAGE_OPEN_QUESTIONS.md).

## Goal

Replace JSON replay writing for new recordings with the `.br` binary archive format while keeping replay playback functional, preserving crash recovery, supporting both file and MySQL backends, and retaining temporary legacy JSON read compatibility.

## Non-Goals for v1

- No migration of legacy JSON replays to `.br`
- No secondary indexes beyond the tick index
- No spatial query support
- No in-game debug query optimization
- No framed/chunked compression or memory-mapped IO

## Recommended Delivery Strategy

Implement this as a vertical slice instead of building the entire binary system at once.

Recommended order:

1. Define the binary/archive contracts.
2. Build append-log writing and recovery.
3. Build finalized archive writing.
4. Build archive reading and playback loading.
5. Integrate with file and MySQL backends.
6. Add export/query filtering on top of the binary reader.
7. Keep legacy JSON reading until its future removal is explicitly scheduled.

This keeps each phase testable and avoids a large all-or-nothing rewrite.

## Phase 1: Freeze the Format Contract

Deliverables:

- A single source of truth for the binary format layout
- Tag assignments for all supported timeline event types
- Manifest/header schema for `.br`
- Archive entry naming rules
- Rules for string-table encoding, varints, checksums, and tick index layout

Implementation tasks:

- Define the `.br` archive structure:
	- `manifest.json` as the manifest entry at the archive root
	- `replay.bin` as the replay payload entry at the archive root
	- reserve the `chunks/` prefix for future chunk payload entries
	- reserve the `meta/` prefix for future auxiliary metadata entries
- Define the manifest fields used in v1:
	- `formatVersion` as a simple integer, starting at `1`
	- `recordedWithVersion`
	- `minimumViewerVersion`
	- `payloadChecksum`
	- `payloadChecksumAlgorithm`
- Define the event tag table for every `TimelineEvent` subtype.
- Define exact binary encodings for:
	- varint
	- UTF-8 strings
	- primitive numeric fields
	- string-table references
	- record framing
	- tick index entries
- Define each tick-index entry as storing both the checkpoint tick and the byte offset.
- Define unknown event tags as a hard failure: abort the entire replay load immediately, surface a clear incompatibility/corruption error, and log the tag plus replay version metadata for diagnosis.

Exit criteria:

- A developer can implement reader and writer code without making format guesses.
- The format spec is stable enough to write golden-file tests.

## Phase 2: Introduce Storage Abstractions

Deliverables:

- Clear interfaces between recording, finalized archive writing, loading, and backend persistence

Implementation tasks:

- Identify the current JSON serialization boundary in the replay save/load path.
- Introduce explicit abstractions for:
	- live event append writer
	- replay finalizer
	- finalized replay reader
	- replay format detector
- Keep the `TimelineEvent` model unchanged so the storage rewrite stays isolated to codecs and storage services.
- Remove direct assumptions that replay payloads are always JSON text.

Suggested interfaces:

- `ReplayFormatDetector`
- `ReplayAppendLogWriter`
- `ReplayFinalizer`
- `ReplayArchiveReader`
- `ReplayStorageCodec`

Exit criteria:

- The existing code can choose serialization format through an interface instead of hardcoding Gson/JSON.

## Phase 3: Append-Log Writer for Active Recordings

Deliverables:

- Append-only temporary recording files with self-framed records and CRC32C
- Incremental string-table definition records
- Buffered flush scheduling

Implementation tasks:

- Implement a temp append-log writer that writes:
	- `recordLength`
	- `recordType`
	- payload
	- CRC32C
- Implement `DEFINE_STRING` emission on first string use.
- Maintain per-recording writer state:
	- string table
	- output stream/buffer
	- flush timing
	- current tick checkpoint state
- Ensure multiple simultaneous recordings each get isolated temp-file state.
- Keep the temp append-log uncompressed.
- Flush once per second, as chosen in the design notes.

Tests:

- appending multiple event types
- string reuse vs first definition
- CRC generation correctness
- independent writers for simultaneous recordings
- truncated temp file recovery cases prepared for Phase 4

Exit criteria:

- New recordings can stream to disk incrementally without building the full replay payload in memory first.

## Phase 4: Crash Recovery and Finalization

Deliverables:

- Recovery scan for interrupted temp files
- Finalized replay payload generation
- `.br` archive creation with manifest checksum
- Tick index generation at 50-tick intervals

Implementation tasks:

- Implement recovery scanning from the temp append-log start.
- Stop at first truncated or checksum-invalid record.
- Rebuild valid replay state from the surviving prefix.
- Generate the finalized replay payload without per-record CRC and compress that payload with LZ4 before placing it in `replay.bin`.
- Use `org.lz4:lz4-java` as the v1 LZ4 library for finalized replay payload compression and full-payload decompression.
- Build the tick index with 64-bit offsets and 50-tick checkpoints.
- Build the final manifest and archive container.
- Store archive entries with `STORE`, not a second compression layer.
- Treat LZ4 compression as part of finalization output generation before archive insertion.

Tests:

- clean finalization
- truncated tail recovery
- CRC mismatch recovery
- missing index rebuild
- manifest checksum validation
- index offsets for long recordings

Exit criteria:

- A recording can survive a crash up to the last flushed valid batch and finalize into a readable `.br` archive.

## Phase 5: Binary Reader and Playback Loader

Deliverables:

- Archive loader for `.br`
- Manifest compatibility checks
- Decompress-to-heap-byte-array load path
- Lazy event decode from in-memory bytes

Implementation tasks:

- Detect `.br` vs legacy JSON replay payloads.
- Read manifest metadata before playback initialization.
- Enforce `minimumViewerVersion` before attempting playback.
- Load the finalized replay payload into a heap `byte[]`.
- Load the string table and tick index into memory.
- Implement seek-to-tick by nearest checkpoint plus forward decode.
- Decode events lazily instead of pre-materializing every `TimelineEvent`.

Tests:

- load valid `.br` replay
- reject too-new replay versions
- seek to arbitrary checkpoint and decode forward
- fallback behavior if the tick index is absent and must be rebuilt or scanned
- unknown tag failure behavior

Exit criteria:

- Playback can start from a binary replay without relying on JSON deserialization.

## Phase 6: Backend Integration

Deliverables:

- File backend writing `.br` directly
- MySQL backend storing `.br` as binary blob
- Legacy JSON read compatibility retained during transition

Implementation tasks:

- Update file storage code to persist finalized `.br` archives.
- Update MySQL storage code to treat replay payloads as binary data.
- Widen the replay payload column from `MEDIUMBLOB` to `LONGBLOB` before binary `.br` storage ships.
- Add format detection so existing JSON payloads still load.
- Ensure replay listing/metadata code continues working regardless of payload format.

Tests:

- file backend round-trip
- MySQL backend round-trip
- mixed repository with both JSON and `.br` replays
- export/import equivalence between backends

Exit criteria:

- New recordings save as binary on both supported backends, while existing JSON replays still play.

## Phase 7: Export and Query Filtering

Deliverables:

- Export path using the binary reader
- `player` and `tickRange` filtering with `all` defaults

Implementation tasks:

- Build export as a linear scan over the replay.
- Support filters:
	- `player`
	- `tickRange`
- Treat omitted `player` as `all`.
- Treat omitted `tickRange` as all ticks.
- Use the tick index only to narrow the scan start when a tick range is supplied.
- Do not add spatial, event-type, or world filters in v1.

Tests:

- export full replay with omitted filters
- export single player
- export bounded tick range
- export `player=all`
- export with both player and tick range

Exit criteria:

- Export behavior matches the chosen query model without adding extra indexing complexity.

## Phase 8: Legacy Compatibility and Removal Prep

Deliverables:

- Stable coexistence of JSON and `.br` replay reading
- Clear deprecation path for JSON replay support

Implementation tasks:

- Keep the legacy JSON reader in place during the transition.
- Ensure loader dispatch is reliable for file and MySQL backends.
- Update docs to state:
	- new recordings use `.br`
	- old JSON replays remain readable temporarily
	- JSON support is planned for removal in later version

Exit criteria:

- The codebase can operate with mixed legacy and binary replays until JSON support is intentionally removed.

## Testing Strategy

Prioritize deterministic tests under `src/test/java/`.

Required coverage:

- binary round-trip for each supported event type
- replay compatibility/version gating
- temp append-log truncation and CRC failure recovery
- tick index generation and seek correctness
- file backend and MySQL backend persistence
- mixed legacy/binary replay loading
- export filtering semantics

Also add:

- golden-file tests for a few small `.br` fixtures
- regression tests for malformed archives and unknown tags
- one benchmark-oriented test or harness for representative replay sizes, if practical

## Recommended First Coding Slice

Do not start with the full archive container.

Start with this smallest end-to-end slice:

1. Define event tags and record framing.
2. Implement temp append-log writing for a narrow subset of events.
3. Implement recovery scan for that subset.
4. Finalize into a minimal `.br` archive.
5. Read it back and play it through the existing playback pipeline.

Suggested first event subset:

- `PlayerMove`
- one spawn/despawn event
- one interaction/combat event

That slice will validate the highest-risk decisions early: framing, string definitions, checksums, finalization, version checks, and lazy decode wiring.

## Remaining Small Decisions to Confirm Before Coding

- None at the storage-format level. Remaining implementation choices are now library wiring and code structure details.

## Supporting Documents

The primary long-lived reference documents for this work now live in the main `docs/` folder:

- `docs/BINARY_FORMAT_SPEC.md`
- `docs/ARCHIVE_MANIFEST_SCHEMA.md`
- `docs/DEPRECATIONS.md`

Benchmark notes should be created only after there is real prototype or production-like measurement data to preserve.