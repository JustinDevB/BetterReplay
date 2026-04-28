# BetterReplay Binary Replay Format Specification

This document describes the v1 binary replay format used by BetterReplay.

It is intended to be readable by developers who need to understand how replay data is structured on disk, how the archive is organized, and how the binary payload should be decoded.

For the archive manifest fields specifically, see [ARCHIVE_MANIFEST_SCHEMA.md](ARCHIVE_MANIFEST_SCHEMA.md).

## Scope

This specification covers:

- the `.br` archive layout
- the append-log temp file header used during recording and crash recovery
- the compressed replay payload stored in `replay.bin`
- event record framing
- string-table usage
- tick index structure
- failure behavior for unsupported or malformed content

This specification does not try to define every implementation class or API surface in the plugin.

## Design Goals

The binary format is designed around these goals:

- reduce CPU cost compared with JSON + GZIP
- reduce recording-time memory pressure
- support crash recovery during active recording
- support fast seek by tick during playback
- keep the final replay artifact identical across file and MySQL backends
- keep the v1 format strict and easy to reason about

## High-Level Model

BetterReplay stores a finalized replay as a `.br` archive.

The archive contains:

- `manifest.json`
- `replay.bin`

The `manifest.json` entry provides replay metadata and compatibility information.

The `replay.bin` entry contains the finalized replay payload:

- compressed as a single LZ4-compressed payload
- decompressed fully into a heap `byte[]` when loaded for playback
- decoded lazily from that in-memory byte array as playback advances

The v1 implementation stores `replay.bin` as a single LZ4 frame.

During active recording, BetterReplay first writes an append-only temp file under `replays/.tmp/`.
That append-log has its own fixed file header so metadata needed during crash recovery is persisted before any framed records are appended.

## Archive Layout

The `.br` file is a ZIP-style archive whose entries are stored using `STORE` rather than a second archive-level compression pass.

### Required v1 entries

| Entry name | Required | Purpose |
|-----------|----------|---------|
| `manifest.json` | Yes | Replay metadata, versioning, checksum, and compatibility gate |
| `replay.bin` | Yes | LZ4-compressed finalized replay payload |

### Reserved prefixes

| Prefix | Purpose |
|--------|---------|
| `chunks/` | Future chunk payload entries |
| `meta/` | Future auxiliary metadata entries |

These prefixes are reserved in v1 but are not required to exist.

## Append-Log Temp File

The recording path writes an append-only temp file before finalizing a `.br` archive.

This append-log exists to support:

- low-overhead sequential writes while recording
- crash-safe recovery of the longest valid record prefix
- persistence of replay-level metadata needed before final archive creation

### Append-log v1 file layout

1. fixed append-log header
2. framed record stream

### Fixed append-log header

The header is written once when the temp file is created and flushed immediately.

| Field | Width | Encoding | v1 value |
|-------|-------|----------|----------|
| magic | 4 bytes | raw ASCII bytes | `BRAL` |
| header version | 1 byte | unsigned byte | `0x01` |
| flags | 1 byte | unsigned byte | `0x00` |
| reserved | 2 bytes | zero-filled | `0x00 0x00` |
| `recordingStartedAtEpochMillis` | 8 bytes | little-endian signed int64 | Unix epoch milliseconds |

Total append-log header size: `16` bytes.

### Append-log header rules

- the header must be present before any framed record bytes
- the recording start timestamp must be preserved when finalizing a recovered append-log after a crash
- malformed or truncated headers are a hard recovery failure
- future append-log metadata should be added by extending this header rather than embedding replay-level metadata in normal event records

## Replay Payload Model

After LZ4 decompression, `replay.bin` is treated as a single binary payload with three logical parts:

1. payload header
2. event stream
3. tick index section

The payload ends with an 8-byte little-endian footer that points to the start of the tick index section.

This model matches the chosen goals:

- sequential writes are possible during recording via a temp append-log
- random access is available in finalized replays via the tick index
- the finalized format is still compact and easy to validate

## Payload Header

The payload begins with a small fixed header used to identify the payload and support basic validation.

### Header fields

| Field | Purpose |
|-------|---------|
| magic bytes | Identifies the payload as a BetterReplay binary replay payload |
| payload format version | Matches the archive `formatVersion` conceptually for parser compatibility |
| reserved flags | Reserved for future format-level behavior |
| reserved bytes | Must be zero in v1 |

### Exact v1 header layout

| Field | Width | Encoding | v1 value |
|-------|-------|----------|----------|
| magic | 4 bytes | raw ASCII bytes | `BRPL` (`0x42 0x52 0x50 0x4C`) |
| payload format version | 1 byte | unsigned byte | `0x01` |
| flags | 1 byte | unsigned byte | `0x00` |
| reserved | 2 bytes | zero-filled | `0x00 0x00` |

Total v1 payload header size: `8` bytes.

### v1 header rules

- the header must be present before the first event record
- the payload format version for v1 is `1`
- the primitive byte order for multi-byte numeric fields is little-endian
- unknown or incompatible payload header values must fail replay load immediately

This format remains strict in v1. If the payload header is invalid, the replay is treated as malformed or unsupported.

## Event Stream

The event stream is the ordered sequence of decoded replay events.

Each event type is identified by a numeric tag rather than a string discriminator.

The event stream may also contain `DEFINE_STRING` records, which populate the string table used by later events.

### Record tag table

The record tag namespace is frozen for v1.

| Tag | Record |
|-----|--------|
| `0x00` | `DEFINE_STRING` |
| `0x01` | `PlayerMove` |
| `0x02` | `EntityMove` |
| `0x03` | `InventoryUpdate` |
| `0x04` | `HeldItemChange` |
| `0x05` | `BlockBreak` |
| `0x06` | `BlockBreakComplete` |
| `0x07` | `BlockBreakStage` |
| `0x08` | `BlockPlace` |
| `0x09` | `ItemDrop` |
| `0x0A` | `Attack` |
| `0x0B` | `Swing` |
| `0x0C` | `Damaged` |
| `0x0D` | `SprintToggle` |
| `0x0E` | `SneakToggle` |
| `0x0F` | `EntitySpawn` |
| `0x10` | `EntityDeath` |
| `0x11` | `PlayerQuit` |

### Event stream rules

- records are processed in order
- string definitions must appear before any event that references them
- the finalized payload keeps normal record framing
- the finalized payload does not keep per-record CRC
- payload integrity is validated at the archive/manifest level instead

## Record Framing

Each finalized replay payload record uses explicit length framing.

### Finalized replay payload record

| Field | Encoding | Notes |
|-------|----------|-------|
| `recordLength` | unsigned LEB128 / protobuf-style varint | Length of the record contents after the length field |
| `recordType` | unsigned LEB128 / protobuf-style varint | Event tag or special tag such as `DEFINE_STRING` |
| `payload` | raw bytes | Record-specific payload |

### Framing rules

- `recordLength` includes everything in the record except `recordLength` itself
- every record must be skippable based on `recordLength`
- unknown event tags are a hard failure in v1, even if the replay passed the version gate
- all v1 varints are non-negative and are limited to 5 bytes for `int`-sized values

## String Table

The format uses a string table to avoid repeating UUIDs, names, world names, entity types, and other repeated string values in every event.

### How it works

- when a string is first encountered, the writer emits a `DEFINE_STRING` record
- the string is assigned a numeric index
- later events store the numeric index instead of the full string

### Benefits

- smaller raw payload size
- less repeated string processing during recording and playback
- cleaner event payload structure

### String table rules

- string indices are encoded as varints
- string values are encoded as UTF-8 with a non-negative varint byte-length prefix followed by raw UTF-8 bytes
- a string index is valid only if it has already been defined in the stream or loaded from the finalized index section
- the first defined string uses index `0`

### `DEFINE_STRING` payload layout

| Field | Encoding | Notes |
|-------|----------|-------|
| `stringIndex` | unsigned varint | Must match the next sequential string-table slot |
| `stringValue` | varint length + UTF-8 bytes | Length is the encoded byte length, not character count |

## Integer Encoding

The format uses varint encoding for compact integer storage where fixed-width integers are wasteful.

### Used for

- `recordLength`
- string table indices
- record tags
- other compact integer fields where fixed width is not required

### Exact v1 varint format

- unsigned, non-negative integer encoding only
- 7 payload bits per byte
- high bit set means another byte follows
- little-endian bit packing across bytes (standard unsigned LEB128 / Protocol Buffers varint)
- maximum width is 5 bytes for 32-bit values used in v1 framing and string references

### Not typically used for

- coordinates stored as raw numeric primitives
- offsets in the tick index

## Primitive Field Encoding

The binary payload stores primitive event fields directly in binary form rather than in decimal string form.

Typical examples:

- coordinates as raw doubles
- yaw and pitch as raw floats
- tick values in index entries as explicit 32-bit integers
- offsets as 64-bit integers

This avoids the text formatting and parsing cost that made the JSON path expensive.

### Exact primitive rules

| Primitive | Encoding |
|-----------|----------|
| `boolean` | single byte: `0x00` false, `0x01` true |
| `int32` | 4-byte little-endian two's-complement |
| `int64` | 8-byte little-endian two's-complement |
| `float32` | 4-byte IEEE 754 little-endian |
| `float64` | 8-byte IEEE 754 little-endian |

## Tick Index

The finalized replay payload includes a tick index section used to seek quickly into the replay.

### v1 checkpoint policy

- fixed checkpoint interval of 50 ticks
- each entry stores both:
	- the checkpoint tick
	- the byte offset of the first event record at or before that checkpoint tick
- offsets are stored as 64-bit values
- each index entry is fixed-width at 12 bytes total

### Exact entry layout

| Field | Width | Encoding |
|-------|-------|----------|
| checkpoint tick | 4 bytes | little-endian signed integer, non-negative in valid files |
| byte offset | 8 bytes | little-endian signed integer, non-negative in valid files |

Both values are required in every v1 index entry. Ticks must align to the fixed 50-tick checkpoint interval.

### Exact v1 index section layout

The index section begins at the footer offset and uses this layout:

| Field | Encoding | Notes |
|-------|----------|-------|
| `indexMagic` | 4 raw bytes | ASCII `BRIX` |
| `stringCount` | unsigned varint | Number of finalized string-table entries |
| `strings` | repeated varint length + UTF-8 bytes | Entire finalized string table in index order |
| `tickIndexCount` | unsigned varint | Number of tick-index entries |
| `tickIndexEntries` | repeated fixed-width entries | Each entry is 12 bytes |

After the index section, the payload ends with:

| Field | Width | Encoding |
|-------|-------|----------|
| `indexSectionOffset` | 8 bytes | little-endian signed integer, non-negative in valid files |

The footer points to the first byte of `indexMagic`.

### Why explicit tick + offset entries

- more robust than deriving ticks only from entry position
- easier to validate and debug
- easier to evolve later if index rules change

### Example entries

- tick `0` -> offset of first record for tick 0
- tick `50` -> offset of first record for tick 50
- tick `100` -> offset of first record for tick 100

### Playback seek behavior

To seek to tick `T`:

1. find the nearest checkpoint at or before `T`
2. preload the finalized string table from the index section
3. jump to that stored byte offset
4. decode forward until tick `T` is reached

## Finalized Payload Integrity

The finalized replay payload does not use per-record CRC fields.

Instead, v1 relies on a whole-payload checksum stored in `manifest.json`.

### Integrity rules

- checksum validation applies to the finalized replay payload as a whole
- corruption or checksum mismatch must fail replay load immediately
- checksum algorithm naming is defined in the manifest schema

## Recording-Time Append-Log vs Finalized Payload

The active-recording temp file and the finalized payload are related but not identical.

### Temp append-log

Used only while recording is in progress.

Each temp record contains:

- `recordLength`
- `recordType`
- `payload`
- `crc32c`

Purpose:

- crash resilience
- recovery from truncated or partially written tails

### Finalized payload

Used after clean close or after recovery + finalization.

Each finalized record contains:

- `recordLength`
- `recordType`
- `payload`

Purpose:

- compact playback artifact
- archive portability across file and MySQL backends

## Failure Rules

The v1 format is intentionally strict.

### Hard failures

Replay load must fail immediately when any of the following occur:

- invalid or missing payload header
- invalid or incompatible `formatVersion`
- `minimumViewerVersion` newer than the running plugin version
- manifest checksum mismatch
- malformed record framing
- unknown event tag encountered during replay load
- invalid string-table reference
- invalid tick-index offsets

### Error reporting expectations

When failure occurs, the loader should:

- stop replay load immediately
- show a clear incompatibility or corruption message
- log enough metadata for diagnosis

At minimum, logged metadata should include:

- replay identifier if available
- `formatVersion`
- `recordedWithVersion`
- `minimumViewerVersion`
- unknown event tag value, if relevant

## Versioning Rules

The format uses three separate version-related concepts:

| Field | Purpose |
|-------|---------|
| `formatVersion` | Binary structure/schema compatibility |
| `recordedWithVersion` | Plugin version used to create the replay |
| `minimumViewerVersion` | Lowest plugin version allowed to load/play the replay |

### v1 rule set

- `formatVersion` is a simple integer
- v1 uses `formatVersion = 1`
- `minimumViewerVersion` should only change when playback semantics truly require a newer plugin version
- additive plugin releases do not imply a new `formatVersion`

## Current v1 Defaults Summary

| Decision | v1 choice |
|----------|-----------|
| Archive container | ZIP-style `.br` archive |
| Archive entry compression | `STORE` |
| Replay payload entry | `replay.bin` |
| Manifest entry | `manifest.json` |
| Payload compression | LZ4 |
| Load model | full decompression to heap `byte[]` |
| Record integrity in finalized payload | whole-payload checksum via manifest |
| Tick index interval | 50 ticks |
| Tick index entry contents | explicit tick + 64-bit offset |
| Unknown event tag handling | hard failure |
| `formatVersion` type | integer |
| Initial `formatVersion` | `1` |

## Reader Notes

Readers trying to understand the structure should think about the replay in this order:

1. open the `.br` archive
2. read and validate `manifest.json`
3. read `replay.bin`
4. LZ4-decompress the full payload into memory
5. validate payload header
6. load the tick index and string table support data
7. decode events lazily as needed for playback or export

## Future Evolution

The v1 format intentionally leaves room for future additions without complicating the first implementation.

Reserved future areas include:

- `chunks/` archive entries
- `meta/` archive entries
- richer manifest fields
- additional event tags
- more advanced offline tooling

Those future additions should still preserve the v1 principle that unsupported or ambiguous data fails clearly rather than being partially interpreted.