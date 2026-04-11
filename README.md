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
  - [src/main/java/me/justindevb/replay/util/storage/ReplayStorage.java](src/main/java/me/justindevb/replay/util/storage/ReplayStorage.java)
  - [src/main/java/me/justindevb/replay/util/storage/FileReplayStorage.java](src/main/java/me/justindevb/replay/util/storage/FileReplayStorage.java)
  - [src/main/java/me/justindevb/replay/util/storage/MySQLReplayStorage.java](src/main/java/me/justindevb/replay/util/storage/MySQLReplayStorage.java)

## Features

- Start and stop recordings
- Save recordings to file or MySQL
- List and delete stored replays
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

Permissions:
- replay.start
- replay.stop
- replay.play
- replay.list
- replay.delete
- replay.*

## Configuration

Default config keys are initialized in:
- [src/main/java/me/justindevb/replay/Replay.java](src/main/java/me/justindevb/replay/Replay.java)

### Storage-Type options

Valid values for `General.Storage-Type` are:

- `file`
  - Stores replay data as JSON files under the plugin data folder.
- `mysql`
  - Stores replay data in a MySQL table (`replays`) using the configured `General.MySQL.*` values.

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

Notes:
- If Storage-Type is invalid, plugin falls back to file storage.
- MySQL replay names are stored in a VARCHAR(64) primary key column.

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
