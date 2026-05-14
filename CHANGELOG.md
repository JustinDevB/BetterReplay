# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Frame-by-frame step controls during paused replay; step backward or forward one tick at a time via `⏮`/`⏭` inventory buttons (slots 6–7)
- Variable playback speed controls during active replay; adjust with `⏪ Slower`/`⏩ Faster` inventory buttons (slots 6–7) using configurable step increments
- Current playback speed displayed in the action bar as `[X.Xx]` during playback
- New config keys `Playback.Speed-Step` (default `0.2`) and `Playback.Max-Speed` (default `1.0`) to control speed increment and upper bound
- Finalized binary `.br` replay storage for file and MySQL backends, including crash-safe append-log recording, lazy indexed loading, preserved recording start timestamps, startup recovery of orphaned temp logs, filtered export tooling, hidden benchmark/debug utilities, and temporary legacy JSON compatibility during migration
- Replay protection commands and metadata, protected replay highlighting in `/replay list`, and config-driven retention cleanup with duration parsing
- Optional chunk baseline capture and chunk-aware playback for binary replays, including block-entity support, replay chunk caching, `Playback.Chunk-View-Radius`, `Playback.Chunk-Send-Limit-Per-Tick`, `Playback.Chunk-Clear-Limit-Per-Tick`, `Playback.Chunk-Timing-Diagnostics`, and `Playback.Chunk-Mode`

### Fixed
- `activeSessions` in `RecorderManager` changed to `ConcurrentHashMap` to prevent `ConcurrentModificationException` (#33)
- PacketEvents block-break recording is now rescheduled onto the server thread to avoid Netty-thread contention and unsafe shared-state mutation (#43)
- Held-item swaps and hotbar slot changes are now captured immediately for more accurate replay inventory playback
- Nested replay inventory loss when starting a replay during an active replay (#31)
- Replay controls getting stuck after replay ends (#27)
- Replay export now writes under the plugin data folder
- Chunk playback restore flow now handles unload timing and viewer return cases more reliably
- Replay chunk load probing now frees completed missing-chunk checks before scheduling the next async wave, so higher chunk send limits are not throttled by one-tick queue lag when many surrounding chunks were never recorded
- Replay chunk load probing now runs at `10x` the configured chunk send rate, so fast `missing-replay-chunk` checks do not throttle how quickly the viewer can discover whether nearby chunks were actually recorded
- Config migration now preserves wrapped pseudo-comments, keeps the managed header stable, and avoids accumulating blank lines between root sections

### Changed
- `RecordingStopEvent` now fires synchronously to fix async AntiCheatReplay compatibility
- `ReplayManager` now exposes `listSavedReplaySummaries`, `protectSavedReplay`, `unprotectSavedReplay`, and returns `ReplayDeleteResult` from `deleteSavedReplay`
- Config settings ownership moved out of `Replay` into a dedicated typed, comment-preserving config manager with versioned migrations
- Replay sessions now always start at `1.0x` speed; `Playback.Max-Speed` is enforced to a minimum of `1.0`
- Config keys for list settings were renamed from `list-page-size`/`list-protected-highlight-color` to `List.Page-Size`/`List.Protected-Highlight-Color`; values are auto-migrated on startup
- Update checks now treat `-SNAPSHOT` builds as their corresponding release, and Modrinth publishing metadata now includes Purpur, Spigot, and Bukkit loaders

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
