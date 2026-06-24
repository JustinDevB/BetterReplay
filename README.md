# BetterReplay

BetterReplay is a server-side replay plugin for Paper with Folia-friendly scheduling through FoliaLib.
It records player and nearby entity activity on the server, stores that timeline, and replays it in-game for viewers without requiring a client replay mod.

## What this project is

- Server plugin written in Java 21
- Targets modern Paper APIs
- Uses PacketEvents for packet interception and replay entity updates
- Uses FoliaLib for scheduler and teleport compatibility
- Supports file and MySQL storage backends
- Uses a short-lived replay-list cache for saved replay commands and API listing calls
- New saves use finalized binary `.br` archives with Zstd-compressed payloads; legacy JSON replays and older LZ4 `.br` archives remain readable during the migration window, but pre-`v2` alpha `.br` inventory archives are intentionally unsupported

## How this differs from client-side replay mods

BetterReplay is not a client recording mod.

Server-side approach (this project):
- Runs entirely on the server
- No replay mod required on player clients
- Captures server-observed gameplay state and events
- Plays back by spawning and updating replay entities for a viewer
- Fits moderation, event review, and server-side tooling workflows

Typical client-side replay mod approach:
- Records from a specific client perspective
- Usually requires modded client setup
- Often focuses on free-camera or cinematic editing
- Playback is usually local to the recording client

In short: BetterReplay focuses on server-managed replay workflows and API-driven integration.

## Features

- Server-side recording and in-game playback with no client replay mod required
- Finalized binary `.br` replay archives stored in either file or MySQL backends
- Crash/restart-safe append-log recording with startup recovery of orphaned temporary saves
- Replay protection, retention cleanup, chunk-aware filtered export, and hidden admin diagnostics
- Optional chunk-aware recording and playback for block baselines around recorded players
- Playback controls with pause stepping, variable speed, and live speed feedback
- Viewer safety controls, viewer state restoration, and optional live-player vanish during playback
- Velocity replay handoff for launching playback on a dedicated backend server and returning viewers afterward
- API-first integration support for other plugins
- Optional Floodgate soft dependency support

## Velocity replay handoff

BetterReplay can move viewers to another Velocity backend for playback with `/replay play <name> server:<backend>` or a configured `Velocity.Default-Replay-Server`, then return them to their original server when the replay ends.

This lets a network keep replay viewing isolated on dedicated replay servers while production servers continue recording. Shared MySQL storage is recommended so the origin and replay backend can access the same saved replay list and payloads. Failed or unacknowledged handoffs report a chat error to the viewer.

## Architecture

The plugin is organized around a bootstrap layer, a recording pipeline, a playback pipeline, a storage abstraction, and operator tooling for export, diagnostics, benchmarking, and retention.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the source map, lifecycle walkthrough, chunk pipeline, viewer safety flow, and threading notes.

## Configuration

Configuration is defined through typed settings in `ReplayConfigSetting`, with explicit runtime reload scopes surfaced by `/replay reload`.

See [docs/CONFIGURATION.md](docs/CONFIGURATION.md) for defaults, valid values, file/MySQL examples, reload behavior, and operational notes.

## Commands

All player and admin actions live under `/replay`, including the normal recording/playback workflow plus hidden export, debug, and benchmark utilities.

Replay and recording names must be 1-64 characters long and may not contain control characters or `\ / : * ? " < > | §`.

See [docs/COMMANDS.md](docs/COMMANDS.md) for syntax, permissions, console support, replay-name parsing rules, and output locations.

## Build from source

Requirements:
- Java 21
- Maven

Build:

```bash
mvn -DskipTests package
```

Output jar:
- `target/BetterReplay-<version>.jar`

## API

BetterReplay provides a public API for other plugins to start and stop recordings, manage saved replays, and listen for lifecycle events.

Quick example:

```java
ReplayManager manager = ReplayAPI.get();
manager.startRecording("demo-session", List.of(player), 120);
manager.stopRecording("demo-session", true);
manager.startReplay("demo-session", viewerPlayer);
```

For full documentation of every method, all events, and a complete example plugin, see [docs/API.md](docs/API.md).

## Documentation

Primary docs:

- [docs/API.md](docs/API.md) - public API reference
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) - component layout, lifecycle, and threading model
- [docs/CONFIGURATION.md](docs/CONFIGURATION.md) - config keys, defaults, reload scopes, and examples
- [docs/COMMANDS.md](docs/COMMANDS.md) - command syntax, permissions, and admin utilities
- [docs/BENCHMARKS.md](docs/BENCHMARKS.md) - benchmark command usage, workload presets, and metric definitions
- [docs/BINARY_FORMAT_SPEC.md](docs/BINARY_FORMAT_SPEC.md) - binary replay payload and archive structure notes, including the current `v2` inventory/event split
- [docs/ARCHIVE_MANIFEST_SCHEMA.md](docs/ARCHIVE_MANIFEST_SCHEMA.md) - `manifest.json` field definitions and validation rules
- [docs/DEPRECATIONS.md](docs/DEPRECATIONS.md) - planned feature and compatibility removals
- [docs/planning](docs/planning) - design notes, comparisons, and implementation planning documents

Binary replay note:
- Finalized `.br` archives store the recording start wall-clock timestamp in `manifest.json` as `recordingStartedAtEpochMillis`.
- Active temp append logs also write a fixed file header carrying the same timestamp so final saves can preserve it after crash-safe recovery.
- Current `.br` archives use format version `2` and store equipment-state and storage-inventory payloads as separate raw item-byte records.
- New `.br` archives write `replay.bin` and chunk payloads with Zstd level 1 while preserving read compatibility with existing LZ4 archives; Zstd archives require the current binary compatibility floor.
- Chunk-enabled `.br` archives may also include `chunks/` region entries containing palette-compressed chunk baselines that are decoded lazily during playback.

## Changelog

A full history of changes, additions, and fixes is tracked in [CHANGELOG.md](CHANGELOG.md), following [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Development workflow

Typical contribution flow:

1. Fork the repository
2. Add upstream remote
3. Create a feature branch
4. Implement and test changes
5. Open a pull request

Example branch naming:
- fix/...
- feat/...
- docs/...
