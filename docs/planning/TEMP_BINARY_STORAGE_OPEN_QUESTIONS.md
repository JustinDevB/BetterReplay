# Temporary Binary Storage Open Questions

This document captures the unresolved implementation questions from the binary storage design discussion so they can be reviewed outside chat history.

Use this as a working checklist. For each section, fill in:

- Decision:
- Rationale:
- Follow-up:

## 1. Compression Model vs Random Access

The current plan assumes direct tick-to-byte seeking via the tick index, but it also describes the binary format as using LZ4 block compression.

Open questions:

- Will the event stream be uncompressed, partially compressed, or fully compressed?
- If compressed, will it use framed or chunked compression so the index can point into independently decompressible blocks?
- Will the tick index point to raw event offsets, compressed block offsets, or both?
- If compression remains enabled, what is the exact strategy for random access without scanning from the start of the file?

Decide one:

- Uncompressed event stream with optional compressed side sections
- Framed/chunked compression with block-aware indexing
- No true random access for compressed files until an index rebuild/decompression step

Decision:

- Decompress the whole replay file into a byte buffer when loading for playback.
- Keep the string table and tick index in memory.
- Decode events lazily from the byte buffer as playback advances rather than fully materializing all `TimelineEvent` objects up front.

Rationale:

- This keeps the implementation simple while still avoiding the largest memory cost: fully decoding the replay into Java object graphs.
- For a 50-player, 20-minute replay, the estimated decompressed raw binary payload in memory is roughly **100–130 MB** under the existing assumptions.
- A lighter, movement-dominant replay may land closer to **65–100 MB**.
- If the implementation were to fully decode all events into Java `TimelineEvent` objects up front, the memory cost would rise to roughly **230–420 MB**.
- If both the decompressed bytes and the decoded Java objects are retained at the same time, the total working set could reach roughly **300–550 MB**.
- The chosen approach keeps playback much closer to the raw byte-buffer cost plus small supporting structures, which is materially safer for a live Minecraft server.
- This approach still preserves fast startup: the file is decompressed once, the string table and tick index are loaded, and tick-based playback can decode events on demand from the in-memory buffer.

### Memory estimate basis

- Workload: 50 players, 20 TPS, 20 minutes
- `PlayerMove` events: `50 × 20 × 1,200 = 1,200,000`
- Additional events (`InventoryUpdate`, `HeldItemChange`, `BlockPlace`, `BlockBreak`, `Attack`, `Swing`, etc.): assume ~10% overhead
- Estimated total events: **~1,320,000**

Scaling from the earlier 30-minute / 50-player estimates in the storage comparison document:

- Raw custom binary for ~2,000,000 events: **150–200 MB**
- Scaled to ~1,320,000 events: **~100–130 MB**

Practical interpretation:

- **Chosen model:** byte buffer + string table + tick index + lazy decode = roughly **~100–150 MB** working set
- **Rejected model:** fully decoded Java events = roughly **~230–420 MB**
- **Worst case if both coexist:** roughly **~300–550 MB**

Follow-up:

- Store the replay on disk as a **single compressed payload** rather than framed/chunked compressed blocks.
- Use **LZ4** for the finalized replay payload compression in v1.
- Use a heap **`byte[]`** as the in-memory replay buffer after decompression.
- Rationale for single compressed payload: it matches the chosen load model of “decompress once, then play from memory”, keeps the implementation simple, and still fits the future plan of placing the replay payload inside a larger archive that may also contain chunk data or metadata.
- Rationale for heap `byte[]`: it is the simplest and most maintainable option for v1, works naturally with full-file decompression and lazy decoding, and avoids the extra complexity of direct buffers or memory-mapped files without a meaningful benefit for this design.
- Explicitly defer framed/chunked compression, direct `ByteBuffer`, and memory-mapped file approaches unless future profiling shows a real need.
- Validate the estimate later with a prototype and a representative 50-player replay capture.

## 2. Crash Resilience and Torn Writes

The plan says a crash should not lose the entire recording, but the exact recovery semantics are not yet defined.

Open questions:

- How does the reader detect a partially written final record?
- Will each record include a length prefix?
- Will records or groups of records include a CRC/checksum?
- What is the write flush policy: every event, every tick, or buffered batches?
- Will the implementation call `fsync`/force writes, or rely on OS buffering?
- What amount of data loss is acceptable on process crash vs power loss?

Need to define:

- Record framing format
- Corruption detection mechanism
- Recovery behavior when a truncated tail is found
- Durability guarantees the plugin will actually claim

Decision:

- Use an **append-only temporary recording file** while the replay is actively being recorded.
- Write each record as a **self-framed entry**:
	- `recordLength` as a varint
	- `recordType` as a byte or varint tag
	- `payload`
	- `crc32c` for the record payload (and type if included in the framed bytes)
- Treat the temporary file as the crash-recovery source of truth.
- On normal recording stop:
	- finish the event stream
	- build/write the tick index
	- package the finished replay as the **single compressed payload** chosen in Question 1
	- place that replay payload inside a **`.br` archive** using a ZIP-style container with entries stored using **STORE** (no second compression pass)
	- store the final replay integrity value as a **whole-payload checksum in the archive manifest**
- On crash recovery:
	- scan the temporary append-log from the start
	- stop at the first truncated or checksum-invalid record
	- discard the invalid tail
	- optionally rebuild the tick index and finalize the replay from the valid prefix
- Use **buffered writes** during recording, flushing on a short interval or tick boundary rather than per event.
- Do **not** call `fsync`/forced disk sync on every event or every tick in v1.
- Make the durability claim conservative: after a crash, the plugin should preserve the replay **up to the last successfully flushed valid record batch**, but may lose the most recent buffered tail.
- Keep **per-record CRC32C only in the temporary append-log** used during active recording.
- In the **final compressed replay payload**, keep `recordLength` and normal record framing for parsing, but drop per-record CRC in favor of the archive-manifest checksum.

Rationale:

- A single compressed payload is simple for finished replays, but it is a poor format for live append-and-recover behavior. Using a temporary append-log during recording separates the **recording durability problem** from the **final storage format problem**.
- A length-prefixed record gives the reader a clear way to know where each record ends.
- A CRC32C per record is a good v1 tradeoff: strong enough to detect torn/corrupt tail writes, cheap to compute, and far simpler than more elaborate journaling.
- Scanning until the first invalid record is a standard recovery model for append-only logs and is easy to reason about.
- Buffered writes keep IO overhead low enough for a live server while still making crash recovery practical.
- Avoiding per-tick `fsync` prevents severe disk stalls and TPS impact, especially on cheap/shared hosting.
- This model gives useful crash resilience without overpromising durability that typical Minecraft hosting cannot guarantee.
- A ZIP-style archive with **STORE** is a good fit for a future multi-payload `.br` container because replay data, chunk data, and other assets may already be individually compressed.
- Keeping per-record CRC only in the temp append-log preserves strong recovery behavior where it matters most, while avoiding unnecessary permanent storage overhead in finalized replays.
- Keeping `recordLength` in the final payload still makes parsing, skipping, and lazy decoding straightforward during playback.

### Recommended answers to the Question 2 prompts

- **How does the reader detect a partially written final record?**
	- By failing one of two checks during scan: incomplete `recordLength`/payload bytes or CRC mismatch.
- **Will each record include a length prefix?**
	- Yes. Use a varint `recordLength` on every record.
- **Will records or groups of records include a CRC/checksum?**
	- Yes. Use **CRC32C per record** in the temporary append-log during active recording. In the finalized replay, rely on the archive-manifest checksum instead of per-record CRC.
- **What is the write flush policy: every event, every tick, or buffered batches?**
	- Buffered batches. Flush at a short interval or tick boundary, not per event.
- **Will the implementation call `fsync`/force writes, or rely on OS buffering?**
	- Rely on normal buffered writes during recording; only do a stronger sync/finalization step when the replay is cleanly closed.
- **What amount of data loss is acceptable on process crash vs power loss?**
	- Accept losing the most recent buffered tail. A good v1 expectation is “replay survives up to the last flushed batch, but the newest unflushed data may be lost on crash or power loss.”

### Proposed v1 framing shape

Temporary append-log record:

- `recordLength` (varint)
- `recordType` (varint or 1 byte)
- `payload` (`recordLength` bytes, or `recordLength` includes type + payload depending on implementation choice)
- `crc32c` (4 bytes)

Final replay payload record:

- `recordLength` (varint)
- `recordType` (varint or 1 byte)
- `payload`

The final replay payload keeps framing but omits per-record CRC. Integrity for the finalized replay is checked at the archive/manifest level.

### Expected overhead

For the temporary append-log, the additional framing cost from `recordLength` + `crc32c` is typically about **5–6 bytes per record**.

For a 50-player, 20-minute replay with roughly **1,320,000** records/events:

- extra temp-file size from `recordLength` + CRC32C: roughly **~6–8 MB**
- total framing overhead including `recordType`: roughly **~8–9 MB**

CPU cost of CRC32C is expected to be **very small** relative to the rest of the replay system, even with multiple simultaneous recordings. The main runtime concern remains write/load architecture and allocation behavior, not checksum math.

Example recovery rule:

1. Read `recordLength`
2. Read `recordType`
3. Read `payload`
4. Read `crc32c`
5. Validate checksum
6. If any step fails due to EOF or checksum mismatch, stop and truncate/discard the tail from that point onward

Follow-up:

- Flush buffered writes **once per second** during active recording.
- `recordLength` includes **everything except `recordLength` itself**.
- Keep the temporary append-log **uncompressed** until finalization.
- Support **multiple simultaneous recordings** by giving each active recording its own temp append-log, writer state, and flush schedule.
- Recovery should be **automatic** on startup: detect interrupted temp replay files, scan to the last valid record, discard any invalid tail, rebuild the tick index, and finalize or re-stage the recovered replay.
- Use a **`.br` archive** as the finalized replay container, with a ZIP-style container and entries stored via **STORE**.
- Keep the final replay checksum in the **archive manifest** rather than as per-record CRC in the finalized replay payload.
- Validate the runtime cost of per-record CRC32C on a representative high-player recording, especially with multiple simultaneous recordings.

## 3. Backend Scope: File vs MySQL

The current document is file-oriented, but BetterReplay supports both file and MySQL storage backends.

Open questions:

- Is binary storage v1 file-backend only?
- If MySQL is supported, will it store the entire replay as one blob or split the replay into blocks/rows?
- Will the same on-disk binary format also be the canonical in-database format?
- If MySQL is deferred, what is the migration/compatibility story for installations already using MySQL?

Decide one:

- Phase 1: binary format for file storage only
- Phase 1: binary format for both file and MySQL
- Keep JSON/MySQL unchanged and only migrate file storage first

Decision:

- Use the **binary format everywhere**, including both file storage and MySQL storage.
- Treat the finalized **`.br` archive** as the single canonical replay artifact regardless of backend.
- For file storage, persist the `.br` archive directly on disk.
- For MySQL storage, store the entire finalized `.br` archive as a **single blob in the existing data field**.
- Future archive contents such as chunk data, metadata, manifests, or additional payloads should all live inside the same `.br` archive, so the database still stores one opaque archive blob rather than a set of split rows.

Rationale:

- One canonical replay format across all backends keeps the code path simpler: encode once, decode once, validate once.
- It avoids maintaining separate serialization formats for file and MySQL backends.
- It preserves backend portability: a replay from MySQL can be exported directly as a `.br` file, and a file replay can be inserted into MySQL without re-encoding.
- Storing the full `.br` archive blob in the existing MySQL data field also fits the future plan of including chunk data and other assets inside the archive without requiring a database schema redesign for each new payload type.
- This keeps MySQL schema complexity low in v1. The database stores replay metadata in columns as it already does, and the replay payload remains one binary object.
- It also keeps the plugin's file and MySQL code aligned: the backend changes are about where the archive is persisted, not how the replay itself is structured.

### Recommended answers to the Question 3 prompts

- **Is binary storage v1 file-backend only?**
	- No. Binary storage should be the intended format for both file and MySQL backends.
- **If MySQL is supported, will it store the entire replay as one blob or split the replay into blocks/rows?**
	- Store the entire finalized `.br` archive as **one blob** in the current data field.
- **Will the same on-disk binary format also be the canonical in-database format?**
	- Yes. The same `.br` archive is the canonical replay artifact for both backends.
- **If MySQL is deferred, what is the migration/compatibility story for installations already using MySQL?**
	- MySQL is not being deferred under this decision; migration considerations still apply for existing JSON-backed rows, but the target format is the same `.br` archive blob.

### Practical implications

- The plugin should finalize a replay into a `.br` archive before handing it off to the selected backend.
- File backend: write the archive to disk.
- MySQL backend: write the archive bytes into the replay payload/data column.
- Export/import between backends becomes straightforward because the payload is already in the final transport format.
- Future additions such as chunk data remain encapsulated inside the archive and do not force a database schema redesign.

Follow-up:

- The current MySQL replay payload column is **`MEDIUMBLOB`**, which is too small for the expected `.br` archive growth, especially once chunk data is included.
- Widen the MySQL replay payload column to **`LONGBLOB`** before binary `.br` storage ships.
- Confirm whether any current code assumes the MySQL payload is JSON text and will need to be updated to treat it as binary data.
- Decide the exact archive manifest fields that will be stored consistently across both backends.

## 4. Migration Path for Existing Replays

The binary `.br` archive is now the canonical replay artifact for both file and MySQL backends, but the migration path for existing JSON-based replays should be decided independently from backend scope.

Open questions:

- Will existing JSON file replays be migrated eagerly, lazily on load, or only via an explicit admin command?
- Will existing JSON-backed MySQL rows be migrated eagerly, lazily on load, or only via an explicit admin command?
- Should migration preserve the original JSON replay after successful conversion, replace it in place, or keep both temporarily?
- How should migration failures be reported and retried?
- Should migrated replays be marked with metadata indicating they were converted from legacy JSON?
- Is there a one-time startup migration pass, or is migration always on-demand?

Need to define:

- Migration trigger strategy
- Failure/retry behavior
- Whether original data is retained, replaced, or archived
- How migration status is surfaced to admins

Decision:

- Do **not** migrate existing JSON replays to `.br` archives.
- For an interim period, support **reading/replaying both formats**:
	- legacy JSON replays
	- new `.br` binary replays
- New recordings should always be written in the binary `.br` format.
- Plan to remove JSON replay support in a **future release, with timing still to be determined**.
- Because replay useful life is expected to be short, an explicit migration path is still not worth the added complexity even though the exact removal release/date is not yet scheduled.

Rationale:

- Replay useful life is short, roughly a couple of weeks, so long-term preservation of old JSON replays is not a major product requirement.
- Building and maintaining a migration system adds implementation complexity, failure handling, testing burden, and user-facing edge cases for little practical benefit.
- Supporting both readers temporarily is much simpler than building a conversion pipeline for both file and MySQL backends.
- This approach allows a clean rollout of the new binary format without forcing administrators to run conversions or wait through startup migration tasks.
- Leaving the exact removal version/date open preserves flexibility while still making it clear that JSON support is legacy compatibility rather than a permanent format commitment.

### Recommended answers to the Question 4 prompts

- **Will existing JSON file replays be migrated eagerly, lazily on load, or only via an explicit admin command?**
	- None of the above. Existing JSON file replays are left as-is and read directly by the legacy JSON reader.
- **Will existing JSON-backed MySQL rows be migrated eagerly, lazily on load, or only via an explicit admin command?**
	- None of the above. Existing JSON-backed MySQL rows are left as-is and read directly by the legacy JSON reader.
- **Should migration preserve the original JSON replay after successful conversion, replace it in place, or keep both temporarily?**
	- Not applicable, because no migration/conversion is planned.
- **How should migration failures be reported and retried?**
	- Not applicable, because no migration system is planned.
- **Should migrated replays be marked with metadata indicating they were converted from legacy JSON?**
	- Not applicable, because no migration system is planned.
- **Is there a one-time startup migration pass, or is migration always on-demand?**
	- Neither. There is no migration pass.

### Practical implications

- The replay loader must detect whether a replay is JSON or `.br` and dispatch to the appropriate reader.
- The replay recorder always writes `.br` format for new recordings.
- The plugin should clearly document that JSON replay support is legacy compatibility only and is planned for removal in a future release.
- Once a removal release is explicitly chosen, JSON replay support can be deleted entirely, simplifying the codebase.

Follow-up:

- Document the deprecation timeline for JSON replay support in the changelog and user-facing docs when the binary format ships.
- Ensure the loader can reliably distinguish JSON and `.br` payloads for both file and MySQL backends.
- If and when a removal release is scheduled, decide whether JSON support will be hard-dropped immediately or gated behind a temporary compatibility flag during the transition.

## 5. Versioning Strategy

The document mentions a binary format version in the header, but compatibility behavior is still open.

Open questions:

- How are future event types added without breaking old readers?
- What happens if a replay contains an unknown event tag?
- Will the format reserve unknown-field/extension space, or require strict version matching?

Need to define:

- Version compatibility rules
- Reader behavior on unknown/newer tags
- Whether binary format changes require major, minor, or internal-only compatibility bumps

Decision:

- Carry version metadata in the `.br` replay manifest/header similar to the current JSON strategy:
	- `recordedWithVersion`
	- `minimumViewerVersion`
	- `formatVersion`
- Define `formatVersion` as a **simple integer**, starting at **`1`** for the first binary archive format.
- On replay load, if `minimumViewerVersion` is **newer than the currently running plugin version**, do **not** attempt playback.
- Instead, show a clear message telling the user which minimum plugin version is required.
- Continue the existing philosophy: only bump `minimumViewerVersion` when a change would actually break replay viewing/playback.
- Use `formatVersion` for internal binary structure/schema tracking.
- Keep reader behavior mostly strict in v1: do **not** try to partially interpret unknown future event tags if the replay requires a newer viewer.
- Older JSON-era plugin versions do not need special handling for `.br` files because they only discover legacy replay files (for example `.gz`/JSON-based files) and will not register `.br` replays at all.

Rationale:

- This mirrors an already proven compatibility model from the current JSON implementation.
- `minimumViewerVersion` provides a simple and user-facing compatibility gate without requiring a complicated forward-compatibility system.
- `recordedWithVersion` is useful for diagnostics and support.
- `formatVersion` keeps binary layout changes separate from plugin semantic compatibility.
- Using a simple integer for `formatVersion` keeps parser compatibility rules unambiguous and avoids overlapping responsibilities with plugin semantic versions.
- Rejecting unsupported replays early is much safer than attempting partial playback with unknown event semantics.
- The fact that older JSON-only plugin versions do not even discover `.br` files avoids a large class of backward-compatibility problems automatically.

### Recommended answers to the Question 5 prompts

- **How are future event types added without breaking old readers?**
	- Add the new event type, and if it would break playback in older versions, bump `minimumViewerVersion` accordingly.
- **What happens if a replay contains an unknown event tag?**
	- In supported readers, this should normally be prevented by the `minimumViewerVersion` gate. If encountered anyway, fail the entire replay load/playback immediately with a clear incompatibility or corruption message rather than attempting partial interpretation or skipping the event.
- **Will the format reserve unknown-field/extension space, or require strict version matching?**
	- Prefer mostly strict matching in v1. Keep the format simple and rely on version metadata instead of building a broad extension/ignore-unknown system immediately.

### Practical implications

- Replay metadata must be readable before full playback begins so compatibility can be checked early.
- User-facing errors should clearly state the replay cannot be viewed on the current plugin version and specify the required minimum version.
- `minimumViewerVersion` should only be incremented for genuinely playback-breaking changes, not for every new plugin release.
- `formatVersion` should be incremented when the binary archive or payload structure changes in a way that affects parsing.

Follow-up:

- Use `manifest.json` at the archive root for v1 manifest metadata.
- Use `replay.bin` at the archive root for the finalized replay payload.
- Reserve `chunks/` for future chunk payload entries and `meta/` for future auxiliary metadata entries.
- Use these v1 manifest field names:
	- `formatVersion`
	- `recordedWithVersion`
	- `minimumViewerVersion`
	- `payloadChecksum`
	- `payloadChecksumAlgorithm`
- Ensure unknown-tag failures log the tag value and relevant replay version metadata for diagnosis.

## 6. Tick Index Granularity

The sample index uses coarse tick checkpoints, but the exact interval is not defined.

Open questions:

- Should the index store an offset for every tick or every N ticks?
- What is the tradeoff target between file size and seek speed?
- Should the interval be configurable or fixed in the format?
- If the interval is configurable, how is that recorded in the file header?

Need to define:

- Index checkpoint frequency
- What the index points to exactly
- Rebuild behavior when the index is missing after a crash

Decision:

- Use a **fixed checkpoint interval of 50 ticks** in v1.
- Each index entry should store both:
	- the checkpoint **tick**
	- the **byte offset of the first event record at or before that checkpoint tick**
- Store tick values as explicit per-entry data in the index rather than deriving them only from entry position.
- Store offsets as **64-bit values**.
- Concretely, index entries would look like:
	- tick `0` -> offset of first record for tick 0
	- tick `50` -> offset of first record for tick 50
	- tick `100` -> offset of first record for tick 100
	- and so on
- Keep the interval **fixed in the format** for v1 rather than configurable.
- If the tick index is missing after a crash, rebuild it by scanning the recovered temp/final payload and writing checkpoint entries at the fixed 50-tick cadence.

Rationale:

- A 50-tick interval is a good balance between index size and seek precision.
- At 20 TPS, 50 ticks is only **2.5 seconds** of replay time, so seeking to the nearest checkpoint and scanning forward is cheap.
- With the chosen playback model, the full replay is already decompressed into memory, so the cost of scanning forward from a nearby checkpoint is very small.
- Indexing every tick would increase index size and rebuild cost for little practical benefit.
- A fixed interval is simpler than a configurable one: no extra header fields, no format variability, and fewer edge cases during rebuild/debugging.
- Storing the checkpoint tick explicitly makes the index more robust and self-describing, which helps debugging, validation, corruption checks, and future format evolution.
- Explicit tick values avoid coupling index correctness to assumed entry ordering, making rebuilt indexes and inspection tools easier to reason about.
- Storing the offset of the first record at or before each checkpoint gives deterministic replay startup from any requested tick.
- Using 64-bit offsets makes the format future-proof and avoids ever having to revisit offset-size limits as replay sizes or archive contents grow.
- The extra space cost of 64-bit offsets is negligible because the checkpoint index is very small relative to the replay payload.

### Size intuition

- A 20-minute replay at 20 TPS contains **24,000 ticks**.
- At a 50-tick checkpoint interval, that is only about **480 index entries**.
- Even when each entry stores both an explicit tick value and a 64-bit offset, the total index size remains very small.

### Playback behavior

When a replay seeks to tick `T`:

1. Find the nearest checkpoint at or before `T`
2. Jump to that stored byte offset
3. Decode forward until the requested tick is reached

This keeps seeking simple and predictable without needing per-tick offsets.

Follow-up:

- Validate with a prototype that scanning forward from a 50-tick checkpoint is comfortably fast for long/high-player replays.

## 7. Query Model for Debugging Tools

The debug command plan includes filters by player, tick range, event type, world, and area, but only a tick index is currently defined.

Open questions:

- Are debug filters expected to be scan-based over a selected tick range?
- Will the format include any secondary indexes for player/world/type?
- Are spatial filters expected to be approximate or exact?
- Which debug operations must be fast enough for in-game use, and which can be console-only scans?

Need to define:

- Which filters are guaranteed fast
- Which filters are best-effort/full-scan
- Whether secondary indexes are part of v1 or a later enhancement

Decision:

- Treat export as a **scan-based operation** over the replay data.
- The expected baseline is that an export scans the **entire replay** unless a tick-range filter is provided.
- Support only these export filters in v1:
	- `player`
	- `tickRange`
- Add an **`all` option** for players.
- If `player` is omitted, default to **`all` players**.
- If `tickRange` is omitted, default to **all ticks**.
- Do **not** add secondary indexes for player, event type, world, or any other field.
- Do **not** support spatial filters in v1.
- Do **not** optimize for in-game debug queries in v1; the immediate use case is export, not interactive in-game inspection.

Rationale:

- Speed is not a primary concern for this feature, so a simple full-scan export path is acceptable.
- The existing tick index is already sufficient for narrowing work when a tick range is provided; additional indexes would add format and implementation complexity without a clear need.
- Restricting v1 to `player` and `tickRange` keeps the query model small, testable, and aligned with the actual expected usage.
- An explicit `all` player option, an omitted-`player` default of all players, and an omitted-`tickRange` default of all ticks make the export behavior straightforward and predictable.
- Dropping spatial filters avoids designing approximate-vs-exact semantics that are not currently needed.
- Since there is no identified requirement for in-game debug tools, there is no reason to optimize query latency for interactive use.

Follow-up:

- Document the export command/query syntax so `player=all`, omitted `player`, and omitted `tickRange` clearly mean a full replay export.
- Implement export filtering as a linear scan with optional early narrowing by tick range.
- Revisit secondary indexes only if a future non-export workflow demonstrates a real performance problem.