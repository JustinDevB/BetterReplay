# BetterReplay

BetterReplay is a server-side replay plugin for Paper and Folia-style scheduling.
It records player and nearby entity activity on the server, saves the timeline, and replays it for viewers in-game.

## What this project is

- Server plugin written in Java
- Targets modern Paper APIs
- Uses PacketEvents and FoliaLib for packet handling and scheduling
- Supports two storage backends:
  - local JSON files
  - MySQL

## How this differs from client-side replay mods

BetterReplay is not a client recording mod.

Server-side approach (this project):
- Runs entirely on the server
- No replay mod required on player clients
- Captures server-observed gameplay state and events
- Plays back by spawning and updating replay entities for a viewer
- Good for moderation, event review, and server-side tooling

Typical client-side replay mod approach:
- Records from a specific client perspective
- Usually requires modded client setup
- Often includes advanced free-camera/cinematic editing features
- Playback is usually local to the client recording

In short: BetterReplay focuses on server-managed replay workflows and API-driven integration.

## High-level architecture

- Replay bootstrapping
  - [src/main/java/me/justindevb/replay/Replay.java](src/main/java/me/justindevb/replay/Replay.java)
- Public API entry point
  - [src/main/java/me/justindevb/replay/api/ReplayAPI.java](src/main/java/me/justindevb/replay/api/ReplayAPI.java)
  - [src/main/java/me/justindevb/replay/api/ReplayManager.java](src/main/java/me/justindevb/replay/api/ReplayManager.java)
- Recording lifecycle
  - [src/main/java/me/justindevb/replay/RecorderManager.java](src/main/java/me/justindevb/replay/RecorderManager.java)
  - [src/main/java/me/justindevb/replay/RecordingSession.java](src/main/java/me/justindevb/replay/RecordingSession.java)
- Replay playback lifecycle
  - [src/main/java/me/justindevb/replay/ReplaySession.java](src/main/java/me/justindevb/replay/ReplaySession.java)
- Storage abstraction and implementations
  - [src/main/java/me/justindevb/replay/storage/ReplayStorage.java](src/main/java/me/justindevb/replay/storage/ReplayStorage.java)
  - [src/main/java/me/justindevb/replay/storage/FileReplayStorage.java](src/main/java/me/justindevb/replay/storage/FileReplayStorage.java)
  - [src/main/java/me/justindevb/replay/storage/MySQLReplayStorage.java](src/main/java/me/justindevb/replay/storage/MySQLReplayStorage.java)
  - [src/main/java/me/justindevb/replay/storage/ReplayStorageCodec.java](src/main/java/me/justindevb/replay/storage/ReplayStorageCodec.java)
  - [src/main/java/me/justindevb/replay/storage/ReplayFormatDetector.java](src/main/java/me/justindevb/replay/storage/ReplayFormatDetector.java)

## Features

- Start and stop recordings
- Save recordings to file or MySQL
- List, protect, unprotect, and delete stored replays
- Automatic retention cleanup for expired replays
- Replay sessions for viewers
- API-first integration support for other plugins
- Optional Floodgate soft dependency support

## Commands and permissions

Registered command and permissions are defined in:
- [src/main/resources/plugin.yml](src/main/resources/plugin.yml)

Base command:
- /replay

Subcommands:
- start
- stop
- play
- list
- delete
- protect
- unprotect
- export (hidden admin utility command)
- benchmark (hidden admin diagnostic command)
- debug dump (hidden admin dump command)
- debug info (hidden admin metadata command)

Permissions:
- replay.start
- replay.stop
- replay.play
- replay.list
- replay.delete
- replay.protect
- replay.unprotect
- replay.export
- replay.benchmark
- replay.debug
- replay.*

Hidden export usage:
- `/replay export <name> [player=<name|all>] [start=<tick>] [end=<tick>]` exports a replay to a `.br` file under the plugin `exports/` folder and prints the generated path.
- Replay names may contain spaces as long as all filter arguments come after the full replay name.

Hidden debug dump usage:
- `/replay debug dump <name> [start=<tick>] [end=<tick>]` writes a human-readable text dump under the plugin `dumps/` folder and prints the generated path.
- Replay names may contain spaces as long as all filter arguments come after the full replay name.

Hidden debug info usage:
- `/replay debug info <name>` prints replay metadata including format, recording timestamp, record count, tick length, compressed/decompressed sizes, and version/index details.
- Replay names may contain spaces.

Hidden benchmark usage:
- `/replay benchmark run <small|medium|large|all>` starts an asynchronous synthetic benchmark run and writes both Markdown and JSON reports under the plugin `benchmarks/` folder.
- `/replay benchmark last` prints the most recent report file paths.

## Configuration

Default config keys and migrations are defined in:
- [src/main/java/me/justindevb/replay/config/ReplayConfigSetting.java](src/main/java/me/justindevb/replay/config/ReplayConfigSetting.java)
- [src/main/java/me/justindevb/replay/config/ReplayConfigManager.java](src/main/java/me/justindevb/replay/config/ReplayConfigManager.java)

### Storage-Type options

Valid values for `General.Storage-Type` are:

- `file`
  - Stores replay data under the plugin data folder.
  - New saves now write finalized binary `.br` archives.
  - The loader still auto-detects both legacy JSON payloads and finalized binary `.br` archives through `ReplayStorageCodec` during the transition period.
- `mysql`
  - Stores replay data in a MySQL table (`replays`) using the configured `General.MySQL.*` values.
  - New saves now store finalized binary `.br` archives as blob data.
  - The loader still auto-detects both legacy JSON payloads and finalized binary `.br` archives during the transition period.

These values should be lowercase as shown above.

### File storage example

```yaml
General:
  Check-Update: true
  Storage-Type: file
```

### MySQL storage example

```yaml
General:
  Check-Update: true
  Storage-Type: mysql
  MySQL:
    host: 127.0.0.1
    port: 3306
    database: betterreplay
    user: replay_user
    password: change-me
```

Additional key used by command pagination:

```yaml
list-page-size: 10
```

Retention cleanup keys:

```yaml
Retention:
  Enabled: false
  Max-Age: 30d
  Check-Interval: 1h
  Delete-Partial-Failures: false
  Log-Deletions: true
```

Notes:
- If Storage-Type is invalid, plugin falls back to file storage.
- MySQL replay names are stored in a VARCHAR(64) primary key column.
- Binary `.br` payloads require the replay data column to be `LONGBLOB`; the plugin now widens `data` automatically during storage initialization.
- Protected replays are skipped by both retention cleanup and manual delete commands until they are explicitly unprotected.
- Protection stores required audit metadata: `protectedAt` and `protectedBy`.
- Retention durations accept `s`, `m`, `h`, and `d` suffixes.
- Legacy JSON replay support is temporary compatibility only and is planned for removal in a later version; new recordings should stay on `.br`.
- The hidden `/replay benchmark` command is now always available to senders with `replay.benchmark`, and `General.Enable-Benchmark-Command` has been removed from config

## Build from source

Requirements:
- Java 21
- Maven

Build:

```bash
mvn -DskipTests package
```

Output jar:
- target/BetterReplay-<version>.jar

## API

BetterReplay provides a public API for other plugins to start/stop recordings, manage replays, and listen for lifecycle events.

Quick example:

```java
ReplayManager manager = ReplayAPI.get();
manager.startRecording("demo-session", List.of(player), 120);
manager.stopRecording("demo-session", true);
manager.startReplay("demo-session", viewerPlayer);
```

For full documentation of every method, all events, and a complete example plugin, see the **[API Documentation](docs/API.md)**.

## Documentation

Primary docs:

- [docs/API.md](docs/API.md) - public API reference
- [docs/BENCHMARKS.md](docs/BENCHMARKS.md) - benchmark command usage, workload presets, and metric definitions
- [docs/BINARY_FORMAT_SPEC.md](docs/BINARY_FORMAT_SPEC.md) - v1 binary replay payload and archive structure
- [docs/ARCHIVE_MANIFEST_SCHEMA.md](docs/ARCHIVE_MANIFEST_SCHEMA.md) - `manifest.json` field definitions and validation rules
- [docs/DEPRECATIONS.md](docs/DEPRECATIONS.md) - planned feature and compatibility removals

Binary replay note:
- Finalized `.br` archives now store the recording start wall-clock timestamp in `manifest.json` as `recordingStartedAtEpochMillis`.
- Active temp append-logs also write a fixed file header carrying the same timestamp so final saves can preserve it after crash-safe recovery.

Planning docs:

- [docs/planning](docs/planning) - design notes, comparisons, and implementation planning documents

## Changelog

A full history of changes, additions, and fixes is tracked in **[CHANGELOG.md](CHANGELOG.md)**, following the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

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
