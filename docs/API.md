# BetterReplay API Documentation

BetterReplay exposes a public API that other plugins can use to start/stop recordings, manage replays, and react to lifecycle events.

## Table of Contents

- [Getting the API Instance](#getting-the-api-instance)
- [Maven Dependency](#maven-dependency)
- [ReplayManager Methods](#replaymanager-methods)
  - [startRecording](#startrecording)
  - [stopRecording](#stoprecording)
  - [getActiveRecordings](#getactiverecordings)
  - [startReplay](#startreplay)
  - [stopReplay](#stopreplay)
  - [getActiveReplays](#getactivereplays)
  - [listSavedReplays](#listsavedreplays)
  - [deleteSavedReplay](#deletesavedreplay)
  - [getSavedReplayFile](#getsavedreplayfile)
- [Events](#events)
  - [RecordingStartEvent](#recordingstartevent)
  - [RecordingStopEvent](#recordingstopevent)
  - [RecordingSaveEvent](#recordingsaveevent)
  - [ReplayStartEvent](#replaystartevent)
  - [ReplayStopEvent](#replaystopevent)
- [Full Example Plugin](#full-example-plugin)

---

## Getting the API Instance

All API access starts through `ReplayAPI.get()`, which returns the `ReplayManager` instance. BetterReplay must be loaded before your plugin accesses the API.

Add BetterReplay as a dependency in your `plugin.yml`:

```yaml
depend: [BetterReplay]
```

Then in your plugin code:

```java
import me.justindevb.replay.api.ReplayAPI;
import me.justindevb.replay.api.ReplayManager;

ReplayManager manager = ReplayAPI.get();
```

## Maven Dependency

To compile against the BetterReplay API, add the following to your `pom.xml`:

```xml
<dependency>
    <groupId>me.justindevb</groupId>
    <artifactId>BetterReplay</artifactId>
    <version>1.4.0</version>
    <scope>provided</scope>
</dependency>
```

> **Note:** BetterReplay is not currently published to Maven Central. You will need to build from source and install it to your local Maven repository with `mvn install`, or use a repository manager that hosts it.

---

## ReplayManager Methods

### startRecording

Starts recording a new session that captures player and nearby entity activity.

```java
void startRecording(String name, Collection<Player> players, int durationSeconds)
```

| Parameter | Type | Description |
|---|---|---|
| `name` | `String` | Unique name for this recording session |
| `players` | `Collection<Player>` | The players to record |
| `durationSeconds` | `int` | Duration in seconds. Use `-1` for infinite (manual stop) |

**Example:**

```java
ReplayManager manager = ReplayAPI.get();

// Record two players for 5 minutes
List<Player> targets = List.of(player1, player2);
manager.startRecording("pvp-match-42", targets, 300);
```

```java
// Record a single player indefinitely until manually stopped
manager.startRecording("surveillance", List.of(suspect), -1);
```

---

### stopRecording

Stops a running recording session.

```java
boolean stopRecording(String name, boolean save)
```

| Parameter | Type | Description |
|---|---|---|
| `name` | `String` | The session name used when starting |
| `save` | `boolean` | `true` to save the recording, `false` to discard it |

**Returns:** `true` if the session was found and stopped, `false` otherwise.

**Example:**

```java
ReplayManager manager = ReplayAPI.get();

// Stop and save
boolean saved = manager.stopRecording("pvp-match-42", true);
if (saved) {
    player.sendMessage("Recording saved!");
}

// Stop and discard
manager.stopRecording("surveillance", false);
```

---

### getActiveRecordings

Returns all currently running recording session names.

```java
Collection<?> getActiveRecordings()
```

**Returns:** A collection of active recording session identifiers.

**Example:**

```java
ReplayManager manager = ReplayAPI.get();

Collection<?> active = manager.getActiveRecordings();
player.sendMessage("Active recordings: " + active.size());
for (Object session : active) {
    player.sendMessage(" - " + session.toString());
}
```

---

### startReplay

Starts playing back a saved recording for a viewer. This is an asynchronous operation that loads the replay data and then begins playback on the main thread.

```java
CompletableFuture<Optional<ReplaySession>> startReplay(String replayName, Player viewer)
```

| Parameter | Type | Description |
|---|---|---|
| `replayName` | `String` | Name of the saved replay to play |
| `viewer` | `Player` | The player who will watch the replay |

**Returns:** A `CompletableFuture` containing an `Optional<ReplaySession>`. The optional is empty if the replay was not found, was empty/corrupted, or if the parameters were null.

**Example:**

```java
ReplayManager manager = ReplayAPI.get();

manager.startReplay("pvp-match-42", viewer).thenAccept(optSession -> {
    if (optSession.isPresent()) {
        ReplaySession session = optSession.get();
        viewer.sendMessage("Replay started!");
    } else {
        viewer.sendMessage("Could not start replay.");
    }
});
```

---

### stopReplay

Stops an active replay session.

```java
boolean stopReplay(Object replaySession)
```

| Parameter | Type | Description |
|---|---|---|
| `replaySession` | `Object` | The `ReplaySession` instance to stop |

**Returns:** `true` if the session was a valid `ReplaySession` and was stopped, `false` otherwise.

**Example:**

```java
ReplayManager manager = ReplayAPI.get();

manager.startReplay("pvp-match-42", viewer).thenAccept(optSession -> {
    optSession.ifPresent(session -> {
        // Stop the replay after some condition
        boolean stopped = manager.stopReplay(session);
    });
});
```

---

### getActiveReplays

Returns all currently active replay sessions.

```java
Collection<?> getActiveReplays()
```

**Returns:** A collection of active `ReplaySession` objects.

**Example:**

```java
ReplayManager manager = ReplayAPI.get();

Collection<?> replays = manager.getActiveReplays();
player.sendMessage("Active replays: " + replays.size());
```

---

### listSavedReplays

Lists the names of all saved replays in storage.

```java
CompletableFuture<List<String>> listSavedReplays()
```

**Returns:** A `CompletableFuture` containing a list of replay names.

**Example:**

```java
ReplayManager manager = ReplayAPI.get();

manager.listSavedReplays().thenAccept(names -> {
    player.sendMessage("Saved replays (" + names.size() + "):");
    for (String name : names) {
        player.sendMessage(" - " + name);
    }
});
```

---

### deleteSavedReplay

Deletes a saved replay from storage.

```java
CompletableFuture<Boolean> deleteSavedReplay(String name)
```

| Parameter | Type | Description |
|---|---|---|
| `name` | `String` | The name of the replay to delete |

**Returns:** A `CompletableFuture<Boolean>` — `true` if deleted, `false` if it didn't exist or the delete failed.

**Example:**

```java
ReplayManager manager = ReplayAPI.get();

manager.deleteSavedReplay("pvp-match-42").thenAccept(deleted -> {
    if (deleted) {
        player.sendMessage("Replay deleted.");
    } else {
        player.sendMessage("Replay not found or could not be deleted.");
    }
});
```

---

### getSavedReplayFile

Gets the replay data file on disk. Only applicable when using file-based storage.

```java
CompletableFuture<Optional<File>> getSavedReplayFile(String name)
```

| Parameter | Type | Description |
|---|---|---|
| `name` | `String` | The name of the replay |

**Returns:** A `CompletableFuture` containing an `Optional<File>`. Empty if the file doesn't exist.

**Example:**

```java
ReplayManager manager = ReplayAPI.get();

manager.getSavedReplayFile("pvp-match-42").thenAccept(optFile -> {
    optFile.ifPresent(file -> {
        player.sendMessage("Replay file: " + file.getAbsolutePath());
        player.sendMessage("Size: " + (file.length() / 1024) + " KB");
    });
});
```

---

## Events

BetterReplay fires Bukkit events at key points in the recording and replay lifecycle. Register listeners for these in your plugin as you would any Bukkit event.

All events are in the `me.justindevb.replay.api.events` package.

### RecordingStartEvent

Fired when a recording session starts.

| Method | Return Type | Description |
|---|---|---|
| `getRecordingName()` | `String` | The name of the recording |
| `getTargets()` | `Collection<Player>` | The players being recorded |
| `getSession()` | `RecordingSession` | The recording session object |
| `getDurationSeconds()` | `int` | Configured duration (-1 for infinite) |

**Example:**

```java
@EventHandler
public void onRecordingStart(RecordingStartEvent event) {
    String name = event.getRecordingName();
    int playerCount = event.getTargets().size();
    int duration = event.getDurationSeconds();

    Bukkit.getLogger().info("Recording '" + name + "' started with "
            + playerCount + " player(s), duration: "
            + (duration == -1 ? "infinite" : duration + "s"));
}
```

---

### RecordingStopEvent

Fired when a recording session stops.

| Method | Return Type | Description |
|---|---|---|
| `getSession()` | `RecordingSession` | The recording session that stopped |

**Example:**

```java
@EventHandler
public void onRecordingStop(RecordingStopEvent event) {
    RecordingSession session = event.getSession();
    Bukkit.getLogger().info("A recording session has stopped.");
}
```

---

### RecordingSaveEvent

Fired when a recording is about to be saved. This event is **cancellable** — cancelling it prevents the save.

| Method | Return Type | Description |
|---|---|---|
| `getSession()` | `RecordingSession` | The recording session being saved |
| `isCancelled()` | `boolean` | Whether the event has been cancelled |
| `setCancelled(boolean)` | `void` | Cancel or un-cancel the save |

**Example:**

```java
@EventHandler
public void onRecordingSave(RecordingSaveEvent event) {
    // Conditionally prevent saving
    if (shouldBlockSave()) {
        event.setCancelled(true);
        Bukkit.getLogger().info("Recording save was blocked.");
        return;
    }

    Bukkit.getLogger().info("Recording is being saved.");
}
```

---

### ReplayStartEvent

Fired when a replay playback begins for a viewer.

| Method | Return Type | Description |
|---|---|---|
| `getViewer()` | `Player` | The player watching the replay |
| `getSession()` | `ReplaySession` | The replay session |

**Example:**

```java
@EventHandler
public void onReplayStart(ReplayStartEvent event) {
    Player viewer = event.getViewer();
    viewer.sendMessage("§aReplay playback has started!");
}
```

---

### ReplayStopEvent

Fired when a replay playback ends.

| Method | Return Type | Description |
|---|---|---|
| `getViewer()` | `Player` | The player who was watching |
| `getSession()` | `ReplaySession` | The replay session that ended |

**Example:**

```java
@EventHandler
public void onReplayStop(ReplayStopEvent event) {
    Player viewer = event.getViewer();
    viewer.sendMessage("§eReplay playback has ended.");
}
```

---

## Full Example Plugin

A complete example plugin that uses the BetterReplay API to record PvP matches and manage replays:

```java
package com.example.replayintegration;

import me.justindevb.replay.api.ReplayAPI;
import me.justindevb.replay.api.ReplayManager;
import me.justindevb.replay.api.events.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class ReplayIntegration extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);

        // Start a recording via the API
        ReplayManager manager = ReplayAPI.get();

        // Example: record all online players for 10 minutes
        List<Player> online = List.copyOf(Bukkit.getOnlinePlayers());
        if (!online.isEmpty()) {
            manager.startRecording("auto-record", online, 600);
        }
    }

    @Override
    public void onDisable() {
        ReplayManager manager = ReplayAPI.get();
        manager.stopRecording("auto-record", true);
    }

    @EventHandler
    public void onRecordingStart(RecordingStartEvent event) {
        getLogger().info("Recording started: " + event.getRecordingName());
    }

    @EventHandler
    public void onRecordingStop(RecordingStopEvent event) {
        getLogger().info("Recording stopped.");
    }

    @EventHandler
    public void onRecordingSave(RecordingSaveEvent event) {
        getLogger().info("Recording saved.");
    }

    @EventHandler
    public void onReplayStart(ReplayStartEvent event) {
        getLogger().info(event.getViewer().getName() + " started watching a replay.");
    }

    @EventHandler
    public void onReplayStop(ReplayStopEvent event) {
        getLogger().info(event.getViewer().getName() + " stopped watching a replay.");
    }
}
```

With a `plugin.yml` for this example plugin:

```yaml
name: ReplayIntegration
version: 1.0.0
main: com.example.replayintegration.ReplayIntegration
api-version: '1.21'
depend: [BetterReplay]
```
