# BetterReplay Architecture

BetterReplay is organized around a small set of core subsystems: plugin bootstrap, recording, playback, storage, chunk capture/playback, and operator tooling.

This document is the high-level map for the current codebase. For binary archive details, see [BINARY_FORMAT_SPEC.md](BINARY_FORMAT_SPEC.md). For user-facing operations, see [COMMANDS.md](COMMANDS.md) and [CONFIGURATION.md](CONFIGURATION.md).

## Core components

| Area | Responsibility | Key code |
|---|---|---|
| Bootstrap | Starts PacketEvents, FoliaLib, config, storage, commands, API, retention, and benchmark services | [Replay.java](../src/main/java/me/justindevb/replay/Replay.java) |
| Command surface | Dispatches `/replay` subcommands and routes hidden admin tools | [ReplayCommand.java](../src/main/java/me/justindevb/replay/ReplayCommand.java) |
| Public API | Stable API entry point for integrations and plugins | [ReplayAPI.java](../src/main/java/me/justindevb/replay/api/ReplayAPI.java), [ReplayManager.java](../src/main/java/me/justindevb/replay/api/ReplayManager.java) |
| Recording coordination | Starts, tracks, stops, and recovers recording sessions | [RecorderManager.java](../src/main/java/me/justindevb/replay/RecorderManager.java), [RecordingSession.java](../src/main/java/me/justindevb/replay/RecordingSession.java) |
| Replay session lifecycle | Drives a single viewer's playback session, UI, state safety, and teardown | [ReplaySession.java](../src/main/java/me/justindevb/replay/ReplaySession.java), [ReplayViewerStateManager.java](../src/main/java/me/justindevb/replay/playback/ReplayViewerStateManager.java), [ReplayInventoryUI.java](../src/main/java/me/justindevb/replay/playback/ReplayInventoryUI.java) |
| Playback engine | Applies timeline events, entity updates, block state changes, and chunk overlays | [PlaybackEngine.java](../src/main/java/me/justindevb/replay/playback/PlaybackEngine.java), [ReplayBlockManager.java](../src/main/java/me/justindevb/replay/playback/ReplayBlockManager.java) |
| Storage | Persists and loads replays from file or MySQL, with format detection and codec abstractions | [ReplayStorage.java](../src/main/java/me/justindevb/replay/storage/ReplayStorage.java), [FileReplayStorage.java](../src/main/java/me/justindevb/replay/storage/FileReplayStorage.java), [MySQLReplayStorage.java](../src/main/java/me/justindevb/replay/storage/MySQLReplayStorage.java), [ReplayStorageCodec.java](../src/main/java/me/justindevb/replay/storage/ReplayStorageCodec.java), [ReplayFormatDetector.java](../src/main/java/me/justindevb/replay/storage/ReplayFormatDetector.java) |
| Chunk capture and playback | Captures chunk baselines during recording and streams replay chunk overlays during playback | [ChunkCaptureCoordinator.java](../src/main/java/me/justindevb/replay/chunk/ChunkCaptureCoordinator.java), [ReplayChunkSnapshotSender.java](../src/main/java/me/justindevb/replay/playback/ReplayChunkSnapshotSender.java) |
| Operator tooling | Export, debug, benchmark, and retention services | [ReplayExportCommand.java](../src/main/java/me/justindevb/replay/export/ReplayExportCommand.java), [ReplayDebugCommand.java](../src/main/java/me/justindevb/replay/debug/ReplayDebugCommand.java), [ReplayBenchmarkCommand.java](../src/main/java/me/justindevb/replay/benchmark/ReplayBenchmarkCommand.java), [ReplayRetentionService.java](../src/main/java/me/justindevb/replay/retention/ReplayRetentionService.java) |

## Startup lifecycle

When Paper loads the plugin, [Replay.java](../src/main/java/me/justindevb/replay/Replay.java) coordinates startup in this order:

1. `onLoad()` initializes PacketEvents and registers the packet listener at low priority.
2. `onEnable()` initializes PacketEvents, prewarms chunk registries needed by PacketEvents chunk playback, and creates the FoliaLib scheduler wrapper.
3. The plugin constructs the recorder manager and API manager implementation.
4. Typed configuration is initialized through `ReplayConfigManager`.
5. Viewer state protection is registered as a Bukkit listener.
6. The `/replay` command tree is wired, including export, debug, and benchmark handlers.
7. The public API is exposed through `ReplayAPI.init(...)`.
8. Storage is initialized from `General.Storage-Type`.
9. Retention is started from the configured policy.
10. Pending append logs are recovered so crash-interrupted recordings can still be finalized on startup.
11. BetterReplay registers `vv:proxy_details` and stores ViaVersion proxy-reported client protocol versions for replay skin metadata, falling back to PacketEvents when no valid detail message arrives.

The shutdown path mirrors this: active recordings are closed without finalizing or deleting temp logs so the next startup can recover them, active replay sessions are stopped, retention is stopped, PacketEvents is terminated, and MySQL resources are closed.

## Recording pipeline

Recording starts at the `/replay start` command or through the public `ReplayManager` API.

1. [ReplayCommand.java](../src/main/java/me/justindevb/replay/ReplayCommand.java) or an API caller routes the request into the manager implementation.
2. [RecorderManager.java](../src/main/java/me/justindevb/replay/RecorderManager.java) creates or looks up a [RecordingSession.java](../src/main/java/me/justindevb/replay/RecordingSession.java).
3. PacketEvents listeners and Bukkit-side hooks append timeline events for movement, interaction, inventory/equipment updates, blocks, and lifecycle changes.
4. Optional chunk capture records palette-compressed chunk baselines around tracked players.
5. Active recordings are written to a crash-safe append-log path first.
6. On save, the recording is finalized into a binary `.br` archive for the active storage backend.

Important recording characteristics:

- Held-item swaps, equipment snapshots, and storage inventory snapshots are captured as dedicated event types.
- New binary archives store equipment and storage payloads as separate raw-byte records.
- New binary archives compress finalized timeline and chunk payloads with Zstd level 1; older LZ4 payloads remain readable through manifest metadata or frame magic detection.
- Legacy JSON replays can still be read during the migration window, but new saves are finalized as `.br` archives.
- If the server crashes or restarts while recording, startup recovery can resume and finalize orphaned append logs instead of silently losing them.

## Playback pipeline

A replay session is viewer-centric: one [ReplaySession.java](../src/main/java/me/justindevb/replay/ReplaySession.java) coordinates all playback state for one player.

1. The session loads replay playback data from storage, including timeline and optional chunk data.
2. If the viewer already has an active session, BetterReplay transfers replay inventory and saved viewer state to the new session before stopping the old one.
3. The viewer's live state is captured.
4. Viewer safety rules are applied, including safety mode, vanish, and later restoration behavior.
5. The viewer is teleported asynchronously to the first replay location.
6. The replay inventory UI is shown, with pause, seek, step, speed, and stop controls.
7. A FoliaLib timer drives the timeline tick loop, feeding events into `PlaybackEngine` and `ReplayBlockManager`.
8. On stop, fake entities, block overlays, chunk overlays, and viewer state are restored and the session is removed from the registry.

Playback-specific subsystems:

- `PlaybackEngine` applies timeline events to recorded entities and replay viewers.
- `ReplayBlockManager` handles block snapshots, break stages, and chunk-overlay coordination.
- `ReplayInventoryUI` provides pause, seek, step, speed, and stop controls.
- `ReplayViewerStateManager` preserves the real viewer's live-world location, mode, and flight state and can restore it after disconnect/rejoin.

## Chunk capture and chunk-aware playback

Chunk support is optional and only applies to binary `.br` archives.

During recording:

- `Recording.Chunk-Capture.Enabled` turns baseline capture on.
- `ChunkCaptureCoordinator` tracks chunk interest around recorded players.
- Captured chunk baselines are stored in archive chunk regions alongside the replay timeline.

During playback:

- `ReplayBlockManager` and chunk playback helpers stream replay chunk snapshots around the viewer.
- Replay chunk snapshots are sent only after Paper reports the matching real chunk has been sent to the viewer and one refresh has passed, so late live-world chunks do not overwrite replay chunks after long teleports.
- `Playback.Chunk-Mode` decides whether live chunks are restored immediately when they leave the replay window or only when the replay stops.
- Packet-friendly send and clear limits smooth out chunk overlay cost across ticks.
- Timing diagnostics can log replay chunk preparation, replay load, and live restore timings for MSPT troubleshooting.

## Velocity replay handoff

Velocity-network playback uses the plugin messaging channel `betterreplay:proxy` to move a viewer to another backend before playback starts.

1. `/replay play <name> server:<backend>` verifies that the replay exists from the origin server. If no `server:` argument is supplied, `Velocity.Default-Replay-Server` can provide the backend target.
2. `ReplayTransferManager` sends a `START_REPLAY` plugin message containing the replay name and requested backend, tracks the pending transfer, and reports a chat failure if the request cannot be sent or the proxy does not respond.
3. When the viewer joins the target backend, `ReplayJoinListener` asks the proxy for any pending replay launch.
4. `ReplayLaunchMessageListener` receives `REPLAY_LAUNCH`, starts playback on that backend, and remembers the origin server.
5. When `ReplayStopEvent` fires, the listener sends `REPLAY_FINISHED` so the viewer can be returned to the origin server.

The proxy can send `REPLAY_TRANSFER_FAILED` with the target backend and reason when it cannot move the viewer; the origin server turns that response into a clear chat error. This flow is intended for networks where replay playback runs on a dedicated backend while recordings continue elsewhere. Shared MySQL storage is the practical deployment model because the origin and target backend must both resolve the same replay name and payload.

## ViaVersion proxy client details

On a Velocity network, ViaVersion 5.7.2 or newer can send the player's real client protocol in the `vv:proxy_details` plugin message after the backend connection completes. `ViaProxyDetailsListener` validates the version-1 JSON payload and keeps the reported protocol version only for that player's active backend connection. `SpawnFakePlayer` uses this value to select the player skin-layer metadata index; malformed, unsupported, or absent payloads retain PacketEvents' detected client version. The detail payload is advisory and is not used for permissions or authorization.

## Storage model

BetterReplay keeps storage backend selection behind the [ReplayStorage.java](../src/main/java/me/justindevb/replay/storage/ReplayStorage.java) interface.

- `FileReplayStorage` stores replays under the plugin data folder.
- `MySQLReplayStorage` stores replay payloads in MySQL.
- `ReplayStorageCodec` and `ReplayFormatDetector` allow the loader to distinguish legacy JSON payloads from finalized binary archives.
- New saves are written as binary `.br` archives with a manifest, payload entries, and optional chunk regions.
- Current binary archives use replay format `v2`.
- The `.br` ZIP container still uses stored entries; compression is applied inside `replay.bin` and each independently compressed chunk payload.
- Replay-name and replay-summary listings flow through a shared 5-second cache in `ReplayCache`; stale manager reads refresh the active storage backend and update the cache for commands, tab completion, and API callers.

Compatibility notes:

- Legacy JSON replay loading is temporary compatibility support.
- Older alpha `.br` archives that predate the `v2` inventory/event split are intentionally unsupported by current builds.
- New Zstd-compressed `.br` archives stamp the maintained binary replay compatibility floor so older plugin builds reject them before attempting LZ4 decode.
- Binary payload storage in MySQL requires a `LONGBLOB` data column; initialization widens the column automatically when needed.

## Commands, API, and admin tooling

The same core managers back both commands and integrations:

- `/replay` commands route through [ReplayCommand.java](../src/main/java/me/justindevb/replay/ReplayCommand.java).
- Plugin integrations use [ReplayManager.java](../src/main/java/me/justindevb/replay/api/ReplayManager.java) through [ReplayAPI.java](../src/main/java/me/justindevb/replay/api/ReplayAPI.java).
- Export, debug, and benchmark utilities are thin operator surfaces over the same storage and codec layers.
- Filtered binary exports decode playback data so optional chunk baselines can be carried forward; all-player exports preserve every chunk entry, while player-filtered exports re-encode chunk baselines associated with the included player's movement path.
- Retention and replay protection operate on saved replay metadata rather than the active session layer.

See [API.md](API.md) for the public integration surface and [COMMANDS.md](COMMANDS.md) for the operator-facing command reference.

## Threading and safety model

BetterReplay has to cross async and main-thread boundaries carefully because it combines PacketEvents, Bukkit, FoliaLib, and storage operations.

- Bukkit API access must be scheduled back onto the server thread through FoliaLib when reached from async or packet threads.
- Packet receive/send callbacks can arrive off-thread, so recording code that touches Bukkit state must reschedule safely.
- Viewer teleports during replay startup use `teleportAsync(...)` through FoliaLib for safer Paper/Folia compatibility.
- Chunk recording and playback split Folia work by ownership: tracked-player chunk discovery and viewer packet sends run on entity tasks, while live block/chunk reads run through FoliaLib location tasks on the owning region.
- Shared mutable state used across async work, packet listeners, and scheduled tasks must use thread-safe collections.
- Events that have to be fired synchronously, such as `RecordingStopEvent`, are intentionally dispatched on the main thread.

## Package map

At a package level, the codebase is currently organized like this:

- `api/` public API and events
- `benchmark/` synthetic workload generation and report writing
- `chunk/` chunk baseline capture and decoding support
- `config/` typed config keys, migrations, and reload reporting
- `debug/` replay inspection and dump tooling
- `entity/` recorded-entity wrappers used during playback
- `export/` filtered export support
- `listeners/` PacketEvents and Bukkit listeners that feed recording or playback support
- `playback/` viewer UI, block/chunk playback, and viewer state safety
- `recording/` timeline event types and recording-time structures
- `retention/` retention cleanup policy and scheduling
- `storage/` backend implementations, codecs, archive support, and compatibility
- `util/` supporting cache, update checking, and helpers
- `velocity/` plugin-message helpers for Velocity backend replay handoff and return flow

## Related documents

- [API.md](API.md)
- [COMMANDS.md](COMMANDS.md)
- [CONFIGURATION.md](CONFIGURATION.md)
- [BINARY_FORMAT_SPEC.md](BINARY_FORMAT_SPEC.md)
- [ARCHIVE_MANIFEST_SCHEMA.md](ARCHIVE_MANIFEST_SCHEMA.md)
