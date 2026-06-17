# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- 2026-04-17: Frame-by-frame step controls during paused replay; step backward or forward one tick group at a time via `⏮` and `⏭` inventory buttons
- 2026-04-19: Variable playback speed controls during active replay, current speed action-bar feedback, and config keys `Playback.Speed-Step` plus `Playback.Max-Speed`
- 2026-04-27: Finalized binary `.br` replay storage for file and MySQL backends, including crash-safe append-log recording, lazy indexed loading, preserved recording start timestamps, startup recovery of orphaned temp logs, and temporary legacy JSON compatibility during migration
- 2026-04-27: Hidden admin utilities: `/replay export`, `/replay debug dump`, `/replay debug info`, and `/replay benchmark`, with output written under the plugin data folder
- 2026-04-29: Replay protection commands and metadata, protected replay highlighting in `/replay list`, config-driven retention cleanup with human-readable duration parsing, and deletion safeguards for protected replays
- 2026-05-14: Optional chunk baseline capture and chunk-aware playback for binary replays, including block-entity support, replay chunk caching, `Playback.Chunk-View-Radius`, `Playback.Chunk-Send-Limit-Per-Tick`, `Playback.Chunk-Clear-Limit-Per-Tick`, `Playback.Chunk-Timing-Diagnostics`, and `Playback.Chunk-Mode`
- 2026-05-30: Inventory recording split into dedicated equipment-state and storage-snapshot events backed by raw item bytes, plus regression coverage for the new binary payloads and legacy JSON upgrade path
- 2026-05-31: Replay viewer safety controls: `Playback.Viewer-Safety-Mode`, `Playback.Restore-Viewer-Location-On-Stop`, `Playback.Restore-Viewer-GameMode-On-Stop`, `Playback.Restore-Viewer-Flight-On-Stop`, and `Playback.Restore-Viewer-State-On-Rejoin`
- 2026-06-02: `/replay reload` command to re-read `config.yml`, restart retention scheduling, and report which changed settings apply immediately, only to new sessions, on future checks, or after restart
- 2026-06-02: New config key `Playback.Vanish-Viewer` (default `true`) to hide replay viewers from live players during playback
- 2026-06-04: Expanded bStats telemetry with SimplePie charts for storage type, recording chunk capture, playback viewer safety and chunk playback settings, vanish-viewer, and retention state plus retention age normalization to days when cleanup is disabled
- 2026-06-12: Velocity replay handoff support via `/replay play <name> server:<backend>`, including remote replay launch and return-to-origin flow after playback stops
- 2026-06-14: Config key `Velocity.Default-Replay-Server` to route `/replay play <name>` to a default Velocity replay backend when no `server:<backend>` argument is supplied

### Changed
- 2026-04-11: `RecordingStopEvent` now fires synchronously to fix async AntiCheatReplay compatibility
- 2026-04-19: Update checks now treat `-SNAPSHOT` builds as their corresponding release version
- 2026-04-20: Config settings ownership moved out of `Replay` into a dedicated typed, comment-preserving config manager with versioned migrations
- 2026-04-22: Replay sessions now always start at `1.0x` speed, and `Playback.Max-Speed` is enforced to a minimum of `1.0`
- 2026-04-27: Binary replay archives now use format version `2`; new `.br` inventory payloads store split equipment and storage slot bytes directly, while legacy JSON replay loading remains supported and older alpha `.br` inventory archives are intentionally unsupported
- 2026-04-29: `ReplayManager` now exposes `listSavedReplaySummaries`, `protectSavedReplay`, `unprotectSavedReplay`, and returns `ReplayDeleteResult` from `deleteSavedReplay`
- 2026-04-30: Config keys for list settings were renamed from `list-page-size` and `list-protected-highlight-color` to `List.Page-Size` and `List.Protected-Highlight-Color`, with automatic startup migration
- 2026-04-30: Modrinth publishing metadata now includes Purpur, Spigot, and Bukkit loaders
- 2026-05-14: Modrinth uploads now publish the matching release changelog on `main` and the `[Unreleased]` section for `dev` alpha builds
- 2026-06-04: README content was reorganized into overview sections with dedicated Architecture, Configuration, and Commands documents under `docs/`

### Removed
- 2026-04-28: `General.Enable-Benchmark-Command`; `/replay benchmark` is now always permission-gated through `replay.benchmark`

### Fixed
- 2026-04-13: Replay controls no longer get stuck after replay ends (#27)
- 2026-04-15: Held-item swaps and hotbar slot changes are now captured immediately for more accurate replay inventory playback
- 2026-04-16: Nested replay inventory loss when starting a replay during an active replay (#31)
- 2026-04-17: Backward step controls now move exactly one tick group per click instead of skipping two
- 2026-04-19: `activeSessions` in `RecorderManager` changed to `ConcurrentHashMap` to prevent `ConcurrentModificationException` (#33)
- 2026-04-22: Config migration now preserves wrapped pseudo-comments, keeps the managed header stable, and avoids accumulating blank lines between root sections
- 2026-04-27: Replay export now writes under the plugin data folder
- 2026-04-30: PacketEvents block-break recording is now rescheduled onto the server thread to avoid Netty-thread contention and unsafe shared-state mutation (#43)
- 2026-05-14: Chunk playback restore flow now handles unload timing and viewer return cases more reliably, and replay chunk load probing no longer bottlenecks missing-chunk checks under higher send rates
- 2026-05-30: Equipment polling and dirty inventory polling now reuse shared short-lived caches across concurrent recordings to reduce repeated `ItemStack` serialization work without losing fallback accuracy
- 2026-05-31: Replay playback now returns viewers to their original location and gameplay state by default, including disconnect and rejoin recovery
- 2026-06-02: Replay viewers can no longer pick up live world items during playback, preventing replay inventory lockups from stray pickups
- 2026-06-03: Replay viewer startup teleports now use asynchronous teleports for safer Paper and Folia compatibility
- 2026-06-04: Dev alpha publishing now derives the next `-alpha.N` version from existing Modrinth releases for the current base version instead of `GITHUB_RUN_NUMBER`, so prereleases track the next unreleased line (now `1.5.0-alpha.N`) and workflow renames cannot reset alpha numbering
- 2026-06-06: Recorded-player menu teleports now use FoliaLib asynchronous teleports instead of direct player teleports
- 2026-06-08: Fixed all compiler warnings. Maven build and test runs no longer emit unused annotation-processing or Mockito self-attach agent warnings
- 2026-06-12: Legacy JSON replays whose inventory snapshots upgrade into split equipment/storage events now recreate recorded players correctly after replay seek/skip state reconstruction
- 2026-06-12: Legacy JSON replay loading now accepts `null` inventory content slots from older `inventory_update` snapshots
- 2026-06-12: Saved replay listings and replay-name tab completion now share a 5-second cache that refreshes from storage when stale, and MySQL replay overwrites now update `created_at` so recent listings reflect replaced recordings
- 2026-06-14: Velocity replay handoff failures now send a clear chat error when the transfer request cannot be sent, the proxy reports a failure, or the proxy does not respond
- 2026-06-14: Replay stop cleanup now schedules live block and chunk restore reads on their owning Folia region to avoid asynchronous world access failures
- 2026-06-16: Recording chunk baseline capture now schedules live chunk reads on the owning Folia region to avoid async chunk retrieval failures when chunk recording is enabled
- 2026-06-16: Chunk recording and chunk replay now move tracked-player chunk discovery, replay chunk refresh, legacy live block comparisons, and viewer packet sends onto the proper Folia entity or region scheduler
- 2026-06-16: Recording session entity/player tracking and dirty inventory cache state now use concurrent collections to avoid Folia tick `ConcurrentModificationException` crashes during live recordings
- 2026-06-16: Replay compatibility checks now treat a stable release like `1.5.0` as newer than same-base dev builds such as `1.5.0-alpha.9`, and replay debug info now surfaces incompatible replay metadata or the real storage failure instead of a misleading generic not-found message
- 2026-06-17: Velocity replay handoff now dispatches proxy plugin messages back onto the appropriate scheduler before sending them, avoiding thread-context failures when launching or resuming replay viewing across servers
- 2026-06-17: Replay and recording names are now validated to 1-64 non-control characters without path-reserved symbols, and player-facing replay errors now use component chat sends to avoid main-thread `CraftChatMessage` freezes when invalid or missing replay names are echoed (#76)

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
