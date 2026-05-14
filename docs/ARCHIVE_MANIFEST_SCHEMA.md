# BetterReplay Archive Manifest Schema

This document defines the v1 schema for `manifest.json` inside a `.br` archive.

The goal of the manifest is to provide the metadata needed to:

- identify the archive as a BetterReplay replay
- validate compatibility before playback starts
- validate the integrity of `replay.bin`
- support diagnostics when replay loading fails

For the overall binary replay structure, see [BINARY_FORMAT_SPEC.md](BINARY_FORMAT_SPEC.md).

## Manifest Location

In v1, the manifest is stored as:

- `manifest.json`

at the root of the `.br` archive.

## Required v1 Core Fields

| Field | Type | Required | Purpose |
|-------|------|----------|---------|
| `formatVersion` | integer | Yes | Binary structure/schema compatibility version |
| `recordedWithVersion` | string | Yes | Plugin version that created the replay |
| `minimumViewerVersion` | string | Yes | Minimum plugin version allowed to load/play the replay |
| `recordingStartedAtEpochMillis` | integer | Yes | Wall-clock recording start time in Unix epoch milliseconds |
| `payloadChecksum` | string | Yes | Whole-payload checksum for `replay.bin` |
| `payloadChecksumAlgorithm` | string | Yes | Name of the checksum algorithm used for `payloadChecksum` |

## Optional Chunk Metadata Fields

These additive fields are used only by chunk-enabled archives.

Chunk-disabled v1 archives may omit them entirely. When omitted, readers treat them as the logical defaults shown below.

| Field | Type | Required | Default when omitted | Purpose |
|-------|------|----------|----------------------|---------|
| `hasChunkData` | boolean | No | `false` | Signals that the archive contains optional chunk snapshot entries under `chunks/` |
| `chunkRegionEntryCount` | integer | No | `0` | Number of finalized `.brregion` entries stored under `chunks/` |
| `chunkEntryCount` | integer | No | `0` | Total number of chunk snapshot payloads indexed across all region entries |
| `chunkCoordinateHash` | string | No | absent | Optional lowercase-hex integrity/debug digest over recorded chunk coordinates |
| `chunkPayloadFormat` | string | No | `"BRCS"` when `hasChunkData` is `true` | Declares the uncompressed chunk payload family used by every chunk payload in the archive |
| `chunkPayloadVersion` | integer | No | `1` when `hasChunkData` is `true` | Declares the payload revision within `chunkPayloadFormat` |

Chunk-enabled archives written with the frozen packet-friendly snapshot contract must write:

- `chunkPayloadFormat = "BRCP"`
- `chunkPayloadVersion = 1`

Legacy chunk-enabled archives that omit these fields are interpreted as `BRCS` version `1` for backward compatibility.

## Field Definitions

### `formatVersion`

Type:

- integer

Meaning:

- the structural version of the binary replay format
- used by readers to decide whether they know how to parse the replay artifact

v1 rule:

- the first released binary archive format uses `1`

Example:

```json
"formatVersion": 1
```

### `recordedWithVersion`

Type:

- string

Meaning:

- the BetterReplay plugin version that wrote the replay
- used for diagnostics and support, not as the primary parser gate

Example:

```json
"recordedWithVersion": "1.5.0-SNAPSHOT"
```

### `minimumViewerVersion`

Type:

- string

Meaning:

- the minimum BetterReplay plugin version that may load and play the replay
- used as the user-facing compatibility gate before playback starts

Rules:

- if the running plugin version is older than this value, replay loading must fail before playback begins
- this value should change only when replay playback semantics actually require a newer plugin version

Example:

```json
"minimumViewerVersion": "1.5.0"
```

### `payloadChecksum`

Type:

- string

Meaning:

- checksum of the finalized `replay.bin` payload
- used to detect corruption or malformed archive contents

Representation:

- lowercase hexadecimal string is required for v1
- CRC32C values are stored as 8 lowercase hexadecimal digits

Example:

```json
"payloadChecksum": "7d8f8f2b"
```

### `recordingStartedAtEpochMillis`

Type:

- integer

Meaning:

- Unix epoch millisecond timestamp captured when the recording session started
- provides a replay-level wall-clock anchor for converting replay ticks back to approximate real time

Usage:

- consumers can derive an approximate timestamp for replay tick `t` with `recordingStartedAtEpochMillis + t * 50`
- this is a logical 20 TPS time anchor; it does not preserve per-tick wall-clock drift during lag

Rules:

- must be a positive integer
- must represent the original recording start time, not export time or playback time
- writers should preserve this value when finalizing a recovered temp append-log after a crash

Example:

```json
"recordingStartedAtEpochMillis": 1700000000000
```

### `payloadChecksumAlgorithm`

Type:

- string

Meaning:

- identifies how `payloadChecksum` was generated

Rules:

- readers must reject values they do not support
- the algorithm name should be stable and explicit rather than implied
- v1 uses the exact literal `CRC32C`

Example:

```json
"payloadChecksumAlgorithm": "CRC32C"
```

### `hasChunkData`

Type:

- boolean

Meaning:

- indicates whether this archive carries optional chunk snapshot data under `chunks/`

Rules:

- `false` means the replay behaves like a standard `manifest.json` + `replay.bin` archive
- `true` means the archive may contain one or more `chunks/<world>/r.<regionX>.<regionZ>.brregion` entries
- when `false`, `chunkRegionEntryCount` and `chunkEntryCount` must both be `0` and `chunkCoordinateHash`, `chunkPayloadFormat`, and `chunkPayloadVersion` must be absent

Example:

```json
"hasChunkData": true
```

### `chunkRegionEntryCount`

Type:

- integer

Meaning:

- number of finalized `.brregion` archive entries stored for chunk snapshots

Rules:

- must be non-negative
- must be positive when `hasChunkData` is `true`

Example:

```json
"chunkRegionEntryCount": 3
```

### `chunkEntryCount`

Type:

- integer

Meaning:

- total number of individually indexed chunk snapshot payloads across every finalized `.brregion` entry

Rules:

- must be non-negative
- must be positive when `hasChunkData` is `true`
- must not be smaller than `chunkRegionEntryCount`

Example:

```json
"chunkEntryCount": 418
```

### `chunkCoordinateHash`

Type:

- string

Meaning:

- optional lowercase-hex digest over the recorded chunk coordinate set
- intended for diagnostics and future integrity tooling rather than as the primary archive checksum

Rules:

- if present, must use lowercase hexadecimal
- must be absent when `hasChunkData` is `false`

Example:

```json
"chunkCoordinateHash": "00ff11aa"
```

### `chunkPayloadFormat`

Type:

- string

Meaning:

- identifies the uncompressed payload family used by every chunk payload stored under `chunks/`

Supported v1 values:

- `BRCS` for the legacy block-state baseline payload
- `BRCP` for the frozen packet-friendly chunk snapshot payload

Rules:

- must be absent when `hasChunkData` is `false`
- when present, every chunk payload in the archive must use the same family
- writers using the packet-friendly snapshot contract must write `BRCP`

Example:

```json
"chunkPayloadFormat": "BRCP"
```

### `chunkPayloadVersion`

Type:

- integer

Meaning:

- identifies the revision within `chunkPayloadFormat`

Rules:

- must be absent when `hasChunkData` is `false`
- must be positive when present
- `BRCS` currently uses version `1`
- `BRCP` currently uses version `1`

Example:

```json
"chunkPayloadVersion": 1
```

### v1 checksum representation rules

- `payloadChecksum` must be stored as lowercase hexadecimal
- `payloadChecksumAlgorithm` must be `CRC32C`
- the checksum is calculated over the exact stored bytes of `replay.bin`

## Example v1 Manifest

```json
{
  "formatVersion": 1,
  "recordedWithVersion": "1.5.0-SNAPSHOT",
  "minimumViewerVersion": "1.5.0",
  "recordingStartedAtEpochMillis": 1700000000000,
  "payloadChecksum": "7d8f8f2b",
  "payloadChecksumAlgorithm": "CRC32C",
  "hasChunkData": true,
  "chunkRegionEntryCount": 3,
  "chunkEntryCount": 418,
  "chunkCoordinateHash": "00ff11aa",
  "chunkPayloadFormat": "BRCP",
  "chunkPayloadVersion": 1
}
```

## Validation Order

Readers should validate manifest data before attempting full replay decode.

Recommended order:

1. confirm that `manifest.json` exists
2. parse the JSON successfully
3. confirm all required fields exist
4. validate field types
5. validate `formatVersion`
6. compare `minimumViewerVersion` to the running plugin version
7. read `replay.bin`
8. validate chunk metadata field semantics
9. validate `payloadChecksum` using `payloadChecksumAlgorithm`
10. if `hasChunkData` is true, build or validate the `chunks/` entry inventory

If any required replay-core step fails, the replay must not proceed to playback.

If optional chunk metadata or chunk entry inventory validation fails, the loader should disable chunk sidecar usage, warn, and continue with timeline-only playback.

## Failure Expectations

### Missing manifest

Result:

- hard failure

Reason:

- the archive cannot be trusted or version-gated without metadata

### Missing required field

Result:

- hard failure

Reason:

- replay compatibility or integrity cannot be safely determined

### Unsupported `formatVersion`

Result:

- hard failure

Reason:

- the reader does not know how to parse the binary structure safely

### Inconsistent chunk metadata

Result:

- soft failure of chunk sidecar support; replay continues without chunk snapshots

Reason:

- chunk sidecar data is optional additive metadata and should not block timeline playback by default

### Unsupported `chunkPayloadFormat` or `chunkPayloadVersion`

Result:

- soft failure of chunk sidecar support; replay continues without chunk snapshots

Reason:

- the reader can still play the core replay timeline even if it does not understand the optional chunk payload family

### Viewer version too old

Result:

- hard failure with a clear user-facing incompatibility message

Reason:

- replay semantics are not guaranteed to be valid on the older plugin version

### Unsupported checksum algorithm

Result:

- hard failure

Reason:

- the payload integrity cannot be validated reliably

### Checksum mismatch

Result:

- hard failure

Reason:

- `replay.bin` is corrupted, malformed, or not the expected payload

## Logging Expectations

When manifest validation fails, logs should include enough context for diagnosis.

Recommended logged values:

- archive name or replay identifier if known
- `formatVersion`
- `recordedWithVersion`
- `minimumViewerVersion`
- `recordingStartedAtEpochMillis`
- `payloadChecksumAlgorithm`
- the reason validation failed

## Reserved Future Fields

The v1 manifest is intentionally small.

Possible future additions include:

- replay duration
- tick count
- player count summary
- chunk snapshot payload presence flags
- chunk light-policy hints
- debug/export hints

Those fields are not required for v1 and should not be assumed by readers unless formally added in a later schema revision.

## v1 Summary

The v1 manifest exists to answer three questions before playback begins:

1. can this plugin parse the replay format?
2. is this plugin new enough to play the replay safely?
3. is the payload intact?

If the answer to any of those questions is no, replay load must stop immediately.