# Server-Wide Auto Recording Plan

This document outlines two related recording features:

- a manual `record all players` mode that records every player on the server, including players who join after recording has already started
- an auto-record mode that can continuously record either one named player or all players, breaking recordings into fixed-duration segments

Example: with 30-minute segments enabled, BetterReplay should produce 48 recordings per day.

## Goal

Add two closely related capabilities:

1. A server-wide recording mode that records all online players at session start and automatically enrolls players who join while the session is active.
2. An auto-record mode that monitors either a named player or all players and rolls over to a new replay after a configured segment duration.

## Non-Goals for the First Iteration

- No retroactive capture for time before a player joined the server or before recording was enabled.
- No merging of multiple segment files into a single replay.
- No cross-server or proxy-wide coordination.
- No retention or pruning policy in the first implementation.
- No changes to replay playback format unless the dynamic join flow truly requires them.

## Current Constraints in the Codebase

The current implementation is optimized for a fixed player list chosen when the session starts:

- `RecorderManager.startSession(...)` creates a `RecordingSession` from a `Collection<Player>`.
- `RecordingSession` constructs `EntityTracker` once and treats that tracked-player set as the session scope.
- `EntityTracker` supports removing players but not adding them later.
- `RecordingEventHandler.onQuit(...)` records `PlayerQuit` and removes that player from the tracked set.
- `/replay start` currently requires explicit player names.

This means the missing capability is not mainly storage. The missing capability is a runtime enrollment and rollover controller around the existing recording pipeline.

## Recommended Feature Shape

### 1. Server-Wide Session Mode

Introduce an explicit session scope so a recording can be either:

- `TARGETED`: current behavior, fixed set of players chosen at command time
- `SERVER_WIDE`: current online players plus players who join while the session is active

This can be represented as either:

- a `RecordingScope` enum on `RecordingSession`, or
- a small session-options record passed into `RecorderManager` and `RecordingSession`

The second option is more extensible because the same options object can later carry auto-record metadata.

### 2. Rolling Auto-Record Controller

Add a dedicated coordinator for long-running server-wide recording. Recommended name:

- `AutoRecordController`

Responsibilities:

- resolve the configured target selector
- start a recording session only when at least one target player is present
- listen for `PlayerJoinEvent`
- enroll joining players into the active session when allowed by the session target mode
- stop and save the active session when the configured segment duration is reached
- immediately start the next session if at least one target player is available
- stay idle and continue monitoring if no target player is available yet
- expose whether auto-record is enabled and which segment is active

This controller should own the policy. `RecordingSession` should remain a generic recording primitive.

## Proposed Runtime Design

### Session Options

Introduce a small immutable options object, for example:

```java
public record RecordingSessionOptions(
        RecordingScope scope,
        RecordingTarget target,
        int durationSeconds,
        boolean autoRecordSegment,
        String sessionNamePrefix
) {}
```

`TARGETED` sessions can continue behaving exactly as they do today by creating options equivalent to the current command path.

### Recording Target Model

Manual record-all and auto-record need a target model that can be used both by commands and the public API.

Recommended shape:

```java
public sealed interface RecordingTarget permits RecordingTarget.AllPlayers, RecordingTarget.Players {
    record AllPlayers() implements RecordingTarget {}
    record Players(Set<UUID> playerUuids) implements RecordingTarget {}
}
```

Notes:

- commands can use a player name or the literal word `all`
- the API can accept a single player, a collection of players, or `RecordingTarget.AllPlayers`
- runtime tracking should store UUIDs once a player has been resolved, even if a command started from a player name

This avoids using a magic wildcard internally while still giving commands a simple `all` keyword.

### Dynamic Player Enrollment

Add an explicit method on `RecordingSession` for joining players:

```java
public void addTrackedPlayer(Player player)
```

Recommended behavior when a player is added mid-session:

1. Add the player UUID to `EntityTracker`.
2. Emit a snapshot at the current tick so playback can materialize the player immediately.
3. Capture inventory state right away.
4. Begin including that player in normal per-tick movement capture and event capture from the next tick onward.

The initial snapshot can likely reuse existing event types:

- `PlayerMove` at the current tick with name, world, pose, and location
- `InventoryUpdate` at the current tick

That is preferable to introducing a new `PlayerJoin` timeline event unless playback proves it needs one.

### Quit and Rejoin Semantics

For all-player sessions:

- `PlayerQuit` should still be emitted when a tracked player leaves.
- The player should still be removed from the active tracked set after quit.
- If the same player rejoins later during the same session, `addTrackedPlayer(Player)` should enroll them again and emit a fresh snapshot.

This keeps the current quit behavior intact while enabling re-enrollment.

For single-player auto-record sessions:

- if the tracked player quits, the active segment may continue until its configured duration ends
- while that segment remains active, no new events are emitted until the target rejoins
- when the segment ends, the controller should not start the next segment until the target player is online again

For auto-record-all sessions:

- if the active segment becomes empty because all players left, let the segment run until its duration completes
- when that segment ends, do not start a replacement segment unless at least one player is online

### Where Join Handling Should Live

Do not hide server-wide policy inside `RecordingEventHandler` alone.

Recommended split:

- `RecordingSession` owns low-level operations such as add player, tick, stop, and save.
- `AutoRecordController` owns target monitoring, join handling, idle waiting, and rolling segments.
- `RecorderManager` remains the registry of active sessions and the tick loop owner.

This avoids mixing command policy, server-wide policy, and event serialization logic into one class.

## Command and UX Design

The command proposal should preserve the current targeted-recording workflow and add explicit admin-facing commands for record-all and auto-record.

### Recommended Command Surface

- `/replay start <name> <player1 player2 ...> [durationSeconds]`
- `/replay recordall <name> [durationSeconds]`
- `/replay autorecord start <playerName|all> [segmentMinutes] [prefix]`
- `/replay autorecord stop`
- `/replay autorecord status`

This keeps the existing targeted command unchanged while giving auto-record an explicit target argument.

### Command Semantics

#### `/replay recordall <name> [durationSeconds]`

Purpose:

- start a one-off server-wide recording immediately
- include all players currently online
- enroll players who join until the session stops

Behavior:

- if `durationSeconds` is omitted, create an indefinite session
- if a session with the same name already exists, fail with the same duplicate-session behavior used by `/replay start`
- if auto-record `all` is active, reject this command by default to avoid two overlapping all-player policies competing for the same join events

Example:

```text
/replay recordall evening-build 3600
```

#### `/replay autorecord start <playerName|all> [segmentMinutes] [prefix]`

Purpose:

- enable the continuous rolling recorder for either one named player or all players without requiring a plugin reload

Argument rules:

- `<playerName|all>` is required
- `segmentMinutes` is optional and overrides config for the current runtime session
- `prefix` is optional and overrides config for the current runtime session

Target rules:

- `all` means auto-record all players
- any other value is treated as a player name to monitor
- if the named player is offline when the command is run, the controller should still enter a waiting state and start the first segment when that player joins

Behavior:

- fail if auto-record is already active
- fail if a conflicting manual recording is already active for the same effective target scope
- if at least one target player is currently available, start the first segment immediately
- if no target player is currently available, enter an idle monitoring state and wait for a qualifying player to join
- never write runtime command choices back to config

Examples:

```text
/replay autorecord start all
/replay autorecord start all 30
/replay autorecord start all 30 survival
/replay autorecord start Steve 20 suspect
```

Recommendation: treat the command as a runtime control only. Startup behavior comes from config, but in-game commands never mutate config.

#### `/replay autorecord stop`

Purpose:

- stop the rolling recorder cleanly

Behavior:

- stop scheduling replacement segments
- stop the active segment with save enabled
- leave targeted recordings unaffected

#### `/replay autorecord status`

Purpose:

- expose the current auto-record state for admins

Recommended output:

- enabled or disabled
- target mode: `all` or player name
- active segment replay name
- configured segment duration
- active prefix
- current segment start time
- next scheduled rollover time
- waiting-for-target state when no segment is currently active

### Permission Proposal

Add explicit permissions instead of reusing only `replay.start`:

- `replay.recordall`: start one-off server-wide recordings
- `replay.autorecord`: start, stop, and inspect rolling auto-record

Update `replay.*` to include both new permissions.

This keeps targeted recording permissions separate from full-server capture permissions.

### Help and Tab Completion

Update `/replay` help text and completions to make the new surface discoverable.

Recommended help lines:

- `/replay recordall <name> [seconds] - Start recording all players`
- `/replay autorecord start <player|all> [minutes] [prefix] - Start rolling auto-record`
- `/replay autorecord stop - Stop rolling auto-record`
- `/replay autorecord status - Show rolling auto-record status`

Recommended completion behavior:

- after `/replay autorecord`, suggest `start`, `stop`, and `status`
- after `/replay autorecord start`, suggest online player names plus the literal `all`
- after `/replay autorecord start <player|all>`, suggest the configured default segment minutes as a hint
- after `/replay recordall`, suggest no player names because the command takes none

## Configuration Design

Add a dedicated config section instead of placing these keys under `General`. The config should provide startup defaults and all-player join behavior, but it should not act as a general runtime enable switch.

Suggested shape:

```yaml
Recording:
  Join-Players-During-Active-All-Recordings: true
  Auto-Record:
    Record-On-Startup: false
    Startup-Target: all
    Segment-Duration-Minutes: 30
    Name-Prefix: auto
    Save-Active-Segment-On-Shutdown: true
    Name-Timezone: UTC
```

### Key Semantics

#### `Recording.Join-Players-During-Active-All-Recordings`

- should default to `true`

- controls whether new players are added automatically during an active all-player recording
- applies to `/replay recordall` and auto-record with target `all`
- does not apply to targeted manual recordings or auto-record of a single named player

Required config comment:

- make it explicit that this only affects recordings whose target mode is all players

#### `Recording.Auto-Record.Record-On-Startup`

- when `true`, start the auto-record controller during plugin enable using the configured startup target
- when `false`, auto-record remains off until explicitly started by command or API

Recommendation: use `Record-On-Startup` instead of `Resume-On-Startup`. It is clearer that the plugin is activating auto-record policy on startup, not resuming a serialized in-memory state.

#### `Recording.Auto-Record.Startup-Target`

- the startup target for auto-record
- accepted values should be `all` or a player name
- if the configured player is offline at startup, the controller should enter a waiting state and begin recording when that player joins
- if the value is `all` and no players are online at startup, the controller should wait rather than create a blank segment

#### `Recording.Auto-Record.Segment-Duration-Minutes`

- duration of each segment in minutes
- should be the single canonical duration setting for auto-record mode
- recommended minimum: `1`
- recommended default: `30`

#### `Recording.Auto-Record.Name-Prefix`

- prefix for replay naming
- should be sanitized to a filesystem-safe subset if the storage backend uses file names directly
- recommended default: `auto`

#### `Recording.Auto-Record.Save-Active-Segment-On-Shutdown`

- when `true`, graceful shutdown saves the active segment
- when `false`, graceful shutdown discards the active segment and relies on append-log recovery only for crashes

Recommendation: default this to `true` because discarding a graceful-shutdown segment would be surprising.

#### `Recording.Auto-Record.Name-Timezone`

- controls how timestamps are rendered in generated replay names
- acceptable values should be either `UTC` or a valid Java `ZoneId`

Recommendation: default to `UTC` so generated names are stable across daylight-saving transitions.

### Validation Rules

- reject `Segment-Duration-Minutes <= 0`
- reject blank `Name-Prefix`
- reject prefixes containing characters that are invalid for file-backed replay names
- validate `Name-Timezone` against `ZoneId.of(...)` and fall back to `UTC` with a warning if invalid
- reject blank `Startup-Target`
- log the effective startup auto-record configuration when `Record-On-Startup` is enabled

### Config and Command Interaction

The runtime model should be explicit.

Recommended precedence:

1. in-game command or API call for the current runtime session
2. startup config values
3. built-in enum defaults

Examples:

- plugin startup can auto-start `all` or a named player based on config
- `/replay autorecord start Steve 15` overrides the current runtime session but does not rewrite config
- if config says `Record-On-Startup: true` and an admin stops auto-record in game, it stays stopped until restart, then starts again from config

Recommendation: keep command and API overrides in memory only. They supersede what startup created for the current runtime, but they never mutate `config.yml`.

### Backward Compatibility and Migration

This is a new config section, so migration can be additive:

- add defaults through `ReplayConfigSetting`
- treat all keys as optional with explicit fallbacks
- do not require existing servers to regenerate config files

If a future config version migration system is introduced, these keys should be added without changing existing recording defaults.

## Replay Naming Strategy

Rolling recordings need collision-resistant names.

Recommended format:

```text
<prefix>-yyyy-MM-dd-HH-mm-ss
```

Example:

```text
auto-2026-04-28-10-30-00
```

Recommendations:

- use wall-clock start time for naming, not stop time
- format in server-local time or UTC, but choose one and document it
- if a name collision occurs, append a short numeric suffix rather than failing the entire rollover

## Rollover Behavior

For a 30-minute configuration, the controller should produce one replay per 30-minute window while eligible targets are present.

Recommended rollover sequence:

1. Detect that the active segment duration has been reached.
2. Stop the current session and trigger save.
3. If at least one target player is available, immediately create the next session.
4. If no target player is available, switch to a waiting state instead of creating a blank session.
5. Continue join enrollment against the new active session when the target mode allows it.

Important detail:

`RecordingSession.stop(true)` already persists asynchronously after the append-log is finalized. That means a replacement session can be started immediately after stop without waiting for storage completion.

This keeps recording gaps as small as possible.

## Startup and Shutdown Behavior

### Startup

When `Record-On-Startup` is enabled:

1. load config
2. recover pending append logs first
3. start the controller
4. resolve the configured startup target
5. create the initial session only if at least one target player is available, otherwise enter a waiting state

Recovery should happen before auto-record starts, otherwise a crash-recovered segment and a new live segment can overlap in confusing ways.

### Shutdown

On plugin disable:

- stop the controller first so it does not schedule a replacement segment during shutdown
- stop the active session with save enabled if the plugin is performing a normal shutdown
- preserve the existing append-log crash recovery behavior for hard crashes

## Storage and Playback Impact

This feature should fit the current binary append-log and replay archive flow.

Expected storage impact:

- more concurrent event volume because all players are tracked
- many more replay files for auto-record mode
- more frequent save operations due to rolling segments

Expected playback impact:

- no new format requirement if a mid-session join is represented by existing `PlayerMove` and `InventoryUpdate` events
- existing `PlayerQuit` remains sufficient for despawn behavior

Only add a new timeline event type if testing shows that playback cannot consistently spawn a late-joining player from the first `PlayerMove` snapshot.

## Concurrency and Threading Notes

Keep all enrollment and rollover decisions on the server thread.

Specific rules:

- `PlayerJoinEvent` enrollment must run synchronously on the server thread
- session creation and stop decisions must stay on the same thread as the existing tick loop
- async storage completion must not mutate active session state directly

This aligns with the project rule that Bukkit API access must remain on the server thread.

## Recommended Implementation Sequence

### Phase 1: Add Session Options and Dynamic Enrollment

- introduce `RecordingScope` or `RecordingSessionOptions`
- add `EntityTracker.addPlayer(UUID)` or `addPlayer(Player)` support
- add `RecordingSession.addTrackedPlayer(Player)`
- add regression tests for late join and rejoin in the same session

### Phase 2: Add Manual Server-Wide Recording

- add a new command path for `/replay recordall`
- seed the session with `Bukkit.getOnlinePlayers()`
- enroll players who join after session start
- add tests for command parsing and join-time enrollment

### Phase 3: Add Auto-Record Controller

- create `AutoRecordController`
- wire startup and shutdown behavior
- add target resolution, waiting-state handling, and segment naming
- add tests for exact rollover counts and replacement-session startup

### Phase 4: Config and Documentation

- add config keys and validation
- update `README.md`
- update `docs/API.md` for record-all and auto-record API methods
- add `CHANGELOG.md` entries for the user-facing feature

## Test Plan

The feature needs regression coverage before implementation is considered complete.

### Unit Tests

- starting a server-wide session seeds all online players
- player joining an active server-wide session is added exactly once
- player quitting and rejoining during the same session is recorded correctly
- a targeted session still does not auto-enroll unrelated players
- auto-record for a named offline player waits and starts on join
- auto-record `all` does not create a blank segment when the server is empty
- an active segment may complete after all tracked players leave, but no replacement starts until a target is present
- rollover at 30 minutes creates the next segment and keeps recording active
- controller does not start duplicate active segments
- generated names are unique and deterministic

### Integration-Style Tests

- startup with auto-record enabled starts a live segment after append-log recovery
- startup with `Record-On-Startup` enabled waits correctly when the configured target is absent
- shutdown stops the active segment without scheduling a replacement
- async save failure of one segment does not prevent the next segment from running

### Performance Checks

- measure tick cost with many simultaneously tracked players
- measure replay count growth for small segment durations
- confirm that packet listener registration does not duplicate unexpectedly across rollovers

## Risks and Open Questions

### 1. Event Volume

Recording all players continuously can multiply movement and inventory traffic quickly. If tick cost becomes unacceptable, the next optimization step should be shared capture infrastructure rather than multiple specialized server-wide sessions.

### 2. Initial Snapshot Completeness

Late joiners will need a good enough initial snapshot for playback to feel correct. If `PlayerMove` plus `InventoryUpdate` is insufficient, the design may need an explicit lifecycle event for player appearance.

### 3. Replay Proliferation

Auto-record mode can create large numbers of replays. At 30 minutes per segment, the plugin will create 48 recordings per day. Storage growth and future retention tooling should be tracked as follow-up work.

### 4. API Surface

Expose both manual all-player recording and auto-record through additive `ReplayManager` methods rather than changing the meaning of `startRecording(String, Collection<Player>, int)`.

Recommended API additions:

```java
boolean recordAll(String name, int durationSeconds);
boolean startAutoRecording(String namePrefix, RecordingTarget target, int segmentDurationSeconds);
boolean stopAutoRecording(boolean saveActiveSegment);
Optional<AutoRecordingStatus> getAutoRecordingStatus();
```

Compatibility guidance:

- keep `startRecording(String, Collection<Player>, int)` for targeted manual recordings
- add a convenience overload for a single player if desired
- use `RecordingTarget.AllPlayers` to signal all players through the API instead of overloading `null` or an empty collection

Recommended convenience overloads:

```java
default boolean startRecording(String name, Player player, int durationSeconds)
default boolean startAutoRecording(String namePrefix, Player player, int segmentDurationSeconds)
default boolean startAutoRecording(String namePrefix, Collection<Player> players, int segmentDurationSeconds)
```

For API semantics:

- a single-player auto-record should monitor that player even while offline
- a collection-based auto-record should monitor any configured player in the collection and start segments only when at least one is online
- `RecordingTarget.AllPlayers` should behave like the command target `all`

## Recommendation Summary

Implement this as a thin controller around the existing recording pipeline rather than a new recording system.

The smallest durable design is:

- keep `RecordingSession` as the core recorder
- teach it to enroll players dynamically
- add an `AutoRecordController` for target monitoring and timed rollovers
- expose `recordAll` and auto-record through both commands and the public API
- keep config limited to startup defaults and the all-player join policy

That approach matches the current architecture, minimizes storage churn, and keeps the first implementation focused on the actual missing behavior.