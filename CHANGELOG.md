# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Modrinth auto-publish via CI with alpha-aware update checking
- `HeldItemChange` event for instant hand swap and slot change recording
- Comprehensive test suite (279 tests across 5 phases)
- Sealed `TimelineEvent` records replacing raw `Map<String, Object>` for type-safe timeline events
- Source organization plan executed (Tier 1 + Tier 2 package restructure)
- Enum-based config settings model with centralized, typed config keys
- Versioned config migration with one-time comment backfill for legacy config files
- Frame-by-frame step controls during paused replay; step backward or forward one tick at a time via `⏮`/`⏭` inventory buttons (slots 6–7)
- Variable playback speed controls during active replay; adjust with `⏪ Slower`/`⏩ Faster` inventory buttons (slots 6–7) using configurable step increments
- Current playback speed displayed in the action bar as `[X.Xx]` during playback
- New config keys `Playback.Speed-Step` (default `0.2`) and `Playback.Max-Speed` (default `1.0`) to control speed increment and upper bound
- Binary replay storage now uses finalized `.br` archives with crash-safe append-log recording, lazy indexed loading, file/MySQL backend support, filtered export tooling, hidden benchmark/debug diagnostics, preserved recording start timestamps, startup recovery of orphaned temp logs, and temporary legacy JSON compatibility during migration
- Replay protection metadata and admin commands for protecting and unprotecting saved replays from manual deletion and retention cleanup
- Config-driven replay retention cleanup with duration parsing, scheduled scans, and protection-aware deletion skipping
- Configurable protected replay highlighting in `/replay list`
- Chunk data recording design document with config, storage container, compression, playback, and teardown plan (`docs/planning/CHUNK_DATA_RECORDING.md`)
- Phase 1 chunk archive contract implementation covering additive manifest metadata, canonical `chunks/` entry naming, finalized `.brregion` and temp-region binary layouts, LZ4 chunk codec IDs, and regression tests
- Phase 2 chunk capture scaffolding with typed recording config keys, shared chunk abstractions, replay playback payload loading, and optional chunk archive injection into binary finalization

### Fixed
- `activeSessions` in `RecorderManager` changed to `ConcurrentHashMap` to prevent `ConcurrentModificationException` (#33)
- PacketEvents block-break recording is now rescheduled onto the server thread to avoid Netty-thread contention and unsafe shared-state mutation (#43)
- Nested replay inventory loss when starting a replay during an active replay (#31)
- Replay controls getting stuck after replay ends (#27)
- Static `Replay.getInstance()` NPEs in test environments (#32)
- Deprecation warnings and unused imports cleaned up
- Config migration comment placement and header ordering for generated configs
- Wrapped YAML pseudo-comment values now load correctly during idempotent config initialization (prevents parse failure on long comment lines)
- Config rewrite now deduplicates managed header text and keeps `Config-Version` at the top for cleaner layout
- Config migration no longer accumulates extra blank lines between `Config-Version` and subsequent root sections

### Changed
- All commands routed through `ReplayManager` API (#25)
- `RecordingStopEvent` now fires synchronously to fix async AntiCheatReplay compatibility
- All `printStackTrace()` calls replaced with proper logger calls
- CI GitHub Actions upgraded to Node.js 24-ready major versions (`actions/checkout@v6`, `actions/setup-java@v5`, `actions/upload-artifact@v7`)
- Config settings ownership moved out of `Replay` into a dedicated comment-preserving config manager
- Replay sessions now always start at `1.0x` speed; `Playback.Max-Speed` is enforced to a minimum of `1.0`
- Generated config output now inserts blank lines between root-level keys/sections for readability
- `ReplayManager.deleteSavedReplay` now returns `ReplayDeleteResult`, and the public API also exposes `listSavedReplaySummaries`, `protectSavedReplay`, and `unprotectSavedReplay`
- Config keys for list settings renamed from `list-page-size`/`list-protected-highlight-color` to `List.Page-Size`/`List.Protected-Highlight-Color`; values are auto-migrated on startup
- Updated to Java 26 runtime, Paper 26.1.2, and PacketEvents 2.12.1
- Expanded Modrinth publishing to include Purpur, Spigot, and Bukkit loaders
- Expanded supported Minecraft game versions in Modrinth publishing (1.21 through 26.1.2)

## [1.4.0] - 2026-04-10

### Added
- Recording version header envelope (`createdBy`, `minVersion`, `timeline`) wrapping all saved recordings
- Auto-detection of legacy raw array format for backward compatibility
- User-friendly error when a recording requires a newer plugin version
- `VersionUtil` with semver comparison helper
- GZIP replay compression with config toggle
- `deleteRecording` API method (#7)
- Full API documentation with examples for all methods and events
- Gradle dependency setup and soft-depend guidance in docs
- Inline tab-completion hints for all subcommands

### Fixed
- Player entity type (`etype`) serialization; replaced `System.out` with logger (#22)
- AABB hitbox ray intersection used instead of cylinder distance check for inventory raytrace (#20)
- Inventory raytrace distance tightened from 1.5 to 1.0 blocks
- `ItemStack` serialization updated to modern API with legacy fallback; handles empty/air items (#19)
- `-SNAPSHOT` suffix stripped before update version comparison (#18)
- Playback controls activating when clicking recorded entities (#16)
- Replay time display using array index instead of recorded tick (#15)
- Inventory tracking via tick-based diff; sync during FF/RW seek (#14)
- Command tab-completion and help text (#13)
- Block state sync during replay seek and FF/RW playback (#10)
- Deterministic block rewind using frozen `sessionBaseline`
- Block crack stages replayed without requiring player UUID
- PacketEvents recording listener properly unregistered on stop
- Formatting in `getReplayFile` method

### Changed
- Upgraded to Paper API 1.21.11 and Java 21 compiler target (#17)
- Switched to project's own bStats dependency instead of PacketEvents' internal shaded copy
- `EntityData<T>` parameterized to eliminate raw type warnings
- Entity position sync on FF/RW while paused

## [1.3.0] - 2026-04-07

### Added
- Root README with architecture, configuration, and API documentation
- GNU GPL v3 license
- Bedrock fake player visibility improvements in replays (#9)

### Fixed
- Replay names with spaces handled correctly in command handlers (#4)
- NPE in `stopRecording` storage refresh (#3)
- Bedrock player disappearance after replay ends (#9)
- Unused and commented-out code cleaned up

### Changed
- Clarified `Storage-Type` valid options in README

## [1.2.0] - 2026-03-28

### Added
- Floodgate integration to properly record Bedrock players (#2)
- Support for recording and replaying in non-default worlds

### Fixed
- Bedrock players now recorded correctly via Floodgate UUID handling
- Duplicate item serializers removed; unified under `ItemStackSerializer`

## [1.1.0] - 2026-01-27

### Added
- MySQL storage backend (#1)
- Stop replay button in playback controls
- Automatic control of play/pause item slot on replay start
- Support for `EntityMapping` to convert Bukkit entities to PacketEvents entities, enabling recording of all entity types
- Mob recording: entities that spawn during a recording are now replayed correctly
- MySQL support with minor bug fixes and QOL improvements
- Developer API events: `RecordingStartEvent`, `RecordingStopEvent`, `ReplayStartEvent`, `ReplayStopEvent` (#5, #6)
- Initial Developer API with `ReplayManager` façade

### Fixed
- Players that disconnect mid-recording are now handled gracefully
- Players no longer remain visible after disconnecting during replay

### Changed
- Rearranged replay control item slots
- Replay start command rewritten for clarity

## [1.0.0] - 2026-03-13

### Added
- Initial public release (v1 prep)
- Core recording system using PacketEvents packet interception
- File-based replay storage (`FileReplayStorage`)
- Playback system with fast-forward and rewind seek controls
- Inventory UI for browsing and starting saved replays
- Item drop recording from player inventory
- Initial commit with base plugin structure

[Unreleased]: https://github.com/DriftN2Forty/BetterReplay/compare/v1.4.0...HEAD
[1.4.0]: https://github.com/DriftN2Forty/BetterReplay/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/DriftN2Forty/BetterReplay/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/DriftN2Forty/BetterReplay/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/DriftN2Forty/BetterReplay/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/DriftN2Forty/BetterReplay/releases/tag/v1.0.0
