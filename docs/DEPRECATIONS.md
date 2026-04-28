# BetterReplay Deprecations

This document tracks features, formats, and compatibility behaviors that are planned for deprecation or removal.

The goal is to give server operators, developers, and maintainers one place to check what is being phased out, why, and what the replacement path is.

## How to Read This Document

Each entry should answer:

- what is being deprecated
- why it is being deprecated
- what replaces it
- when removal is expected
- whether migration is automatic, manual, or intentionally unsupported

## Active Deprecations

### JSON Replay Storage Support

### Status

- Active deprecation

### Scope

- legacy JSON replay files
- legacy JSON-backed MySQL replay payloads

### Replacement

- `.br` binary replay archives

### Why this is being deprecated

JSON replay storage is being phased out because it is materially worse than the binary format in the areas that matter most for production replay workloads:

- higher CPU cost for serialization and deserialization
- much higher memory pressure during recording
- slower replay startup and no efficient seek-by-tick path
- no crash-resilient append-log model
- weaker long-term fit for large recordings and future chunk-inclusive archives

### Current policy

- new recordings should be written in `.br` format
- existing JSON replays remain readable during the transition period
- BetterReplay should support both readers temporarily so older replay data is not immediately lost
- when both legacy JSON and `.br` payloads exist for the same replay name, BetterReplay should prefer the `.br` archive

### Migration policy

- no automatic migration is planned
- no eager migration pass is planned
- no lazy on-load migration is planned
- no explicit admin conversion command is currently planned

This is intentional. Replay useful life is expected to be short enough that building a full migration pipeline is not worth the complexity for v1.

### Removal target

- Future release, date TBD

### Operational impact

Until JSON support is removed:

- loaders must detect whether a replay is JSON or `.br`
- file and MySQL backends may contain mixed replay formats
- file storage should resolve mixed duplicates toward `.br` and avoid duplicate replay names in listings
- documentation should describe JSON support as legacy compatibility, not the forward path

After JSON support is removed:

- the loader can reject JSON replay payloads outright
- the codebase can drop the legacy JSON reader and associated compatibility paths

### Admin guidance

Server admins should treat JSON replay support as temporary compatibility only.

Recommended stance:

- keep old JSON replays only as long as they are operationally useful
- do not assume long-term retention of JSON replay compatibility
- prefer creating new replays only in the binary `.br` format

## Planned Future Entries

This file should also be used for future deprecations such as:

- legacy config keys
- deprecated commands or permissions
- deprecated API methods or events
- compatibility shims retained for old replay formats or internal data structures

## Notes on Benchmark Data

Benchmark notes do not need to exist up front.

They should only be added when there is real prototype or production-like measurement data worth preserving. Until then, benchmark work should be treated as optional supporting evidence rather than required documentation.