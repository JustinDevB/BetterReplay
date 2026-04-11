# Source Organization Plan

An audit of the BetterReplay source code and a proposal for reorganizing it. This document is a plan only — no code changes are included.

---

## Current Package Structure

```
me.justindevb.replay
├── api/
│   ├── ReplayAPI.java
│   ├── ReplayManager.java
│   └── events/
│       ├── RecordingSaveEvent.java
│       ├── RecordingStartEvent.java
│       ├── RecordingStopEvent.java
│       ├── ReplayStartEvent.java
│       └── ReplayStopEvent.java
├── listeners/
│   └── PacketEventsListener.java
├── util/
│   ├── EntityTypeMapper.java
│   ├── FloodgateHook.java
│   ├── ItemStackSerializer.java
│   ├── ReplayCache.java
│   ├── ReplayCompressor.java
│   ├── ReplayExporter.java
│   ├── ReplayObject.java
│   ├── SpawnFakeMob.java
│   ├── SpawnFakePlayer.java
│   ├── UpdateChecker.java
│   └── storage/
│       ├── FileReplayStorage.java
│       ├── MySQLConnectionManager.java
│       ├── MySQLReplayStorage.java
│       └── ReplayStorage.java
├── RecordedEntity.java
├── RecordedEntityFactory.java
├── RecordedMob.java
├── RecordedPlayer.java
├── RecorderManager.java
├── RecordingSession.java
├── Replay.java
├── ReplayCommand.java
├── ReplayManagerImpl.java
├── ReplayRegistry.java
└── ReplaySession.java
```

---

## What's Working Well

- **API package** — `ReplayManager` interface and `ReplayAPI` facade provide a clean public contract for other plugins. The events sub-package follows standard Bukkit conventions.
- **Storage abstraction** — `ReplayStorage` interface with `FileReplayStorage` and `MySQLReplayStorage` implementations is well-designed and easy to extend.
- **Entity hierarchy** — `RecordedEntity` → `RecordedPlayer` / `RecordedMob` with `RecordedEntityFactory` uses proper polymorphism and factory pattern.
- **Listeners package** — Focused, single-class package with clear responsibility.

---

## Issues Identified

### 1. Two Megaclasses (High Priority)

`RecordingSession` and `ReplaySession` are each ~600 lines and combine many unrelated responsibilities:

**RecordingSession** handles:
- Bukkit event listening (block break/place, death, respawn, inventory)
- PacketEvents packet listening (entity metadata)
- Nearby entity tracking and lifecycle
- Inventory change detection and snapshots
- Timeline data serialization
- Block state desync logic
- Per-tick recording logic

**ReplaySession** handles:
- Timeline playback and tick advancement
- Packet sending for entity spawn/destroy/move
- Inventory UI display
- Block state management and re-sync on stop
- Fake item drop spawning
- Player interaction event handling

These classes are difficult to test, extend, or debug in isolation.

### 2. `util/` Is a Catch-All (Medium Priority)

The `util` package contains 10 classes serving unrelated purposes:

| Category | Classes |
|----------|---------|
| Entity spawning | `SpawnFakePlayer`, `SpawnFakeMob` |
| Serialization / IO | `ItemStackSerializer`, `ReplayCompressor`, `ReplayExporter` |
| Data | `ReplayObject`, `ReplayCache` |
| Mapping | `EntityTypeMapper` |
| Plugin hooks | `FloodgateHook`, `UpdateChecker` |

This makes it hard to find things and obscures the relationships between classes.

### 3. Storage Buried Under `util/` (Medium Priority)

Storage is a core architectural concern — the choice between file and MySQL backends affects the entire plugin. It's currently nested at `util/storage/` when it should be a top-level package.

### 4. Untyped Timeline Data (Low Priority)

Timeline events are represented as `Map<String, Object>` throughout the recording and playback pipeline. The contract between what `RecordingSession` produces and what `ReplaySession` consumes is entirely implicit. A typo in a map key or a wrong cast surfaces only at runtime.

### 5. Commands Bypass the API (Low Priority)

`ReplayCommand` directly uses `RecorderManager` (an internal class) instead of going through the `ReplayManager` API. There is already a TODO comment in the code acknowledging this.

### 6. Global Static State in `ReplayRegistry` (Low Priority)

`ReplayRegistry` uses static mutable state to track active replay sessions. This works but makes testing harder and creates hidden coupling.

---

## Proposed Changes

### Tier 1 — High Impact

#### Split `RecordingSession`

Extract focused classes from the current monolithic session:

- **`RecordingEventHandler`** — Bukkit event listener methods (block break/place, death, respawn, inventory changes)
- **`RecordingPacketHandler`** — PacketEvents listener for entity metadata updates
- **`EntityTracker`** — Tracks nearby entities entering/leaving range, manages lifecycle
- **`TimelineBuilder`** — Collects per-tick snapshots into the final timeline data structure

`RecordingSession` would remain as the coordinator that owns the tick loop and delegates to these components.

#### Split `ReplaySession`

Extract focused classes from the current monolithic session:

- **`PlaybackEngine`** — Tick advancement, timeline reading, entity spawn/destroy/move coordination
- **`ReplayBlockManager`** — Block state desync on start, re-sync on stop
- **`ReplayInventoryUI`** — Inventory display when a viewer clicks a replayed player

`ReplaySession` would remain as the coordinator that owns the lifecycle and delegates to these components.

### Tier 2 — Medium Impact

#### Promote `storage/` to Top-Level Package

Move from `me.justindevb.replay.util.storage` to `me.justindevb.replay.storage`. No logic changes, just a package move.

#### Reorganize `util/`

Break the flat utility package into sub-packages:

```
util/
├── spawning/
│   ├── SpawnFakePlayer.java
│   └── SpawnFakeMob.java
├── io/
│   ├── ItemStackSerializer.java
│   ├── ReplayCompressor.java
│   └── ReplayExporter.java
├── EntityTypeMapper.java
├── FloodgateHook.java
├── ReplayCache.java
├── ReplayObject.java
└── UpdateChecker.java
```

### Tier 3 — Low Impact

#### Typed Timeline Events

Create record classes to replace `Map<String, Object>`:

```java
public sealed interface TimelineEvent {
    record PlayerMove(double x, double y, double z, float yaw, float pitch) implements TimelineEvent {}
    record BlockBreak(int x, int y, int z, String blockType) implements TimelineEvent {}
    record ItemChange(int slot, String itemData) implements TimelineEvent {}
    // etc.
}
```

This makes the recording/playback contract compile-time checked and self-documenting.

#### Route Commands Through API

Refactor `ReplayCommand` to use `ReplayManager` (the public API interface) instead of directly accessing `RecorderManager`. This completes the API abstraction.

---

## Proposed Package Structure

```
me.justindevb.replay
├── api/                          (unchanged)
│   ├── ReplayAPI.java
│   ├── ReplayManager.java
│   └── events/
│       └── ...
├── listeners/                    (unchanged)
│   └── PacketEventsListener.java
├── recording/                    (new — extracted from RecordingSession)
│   ├── RecordingEventHandler.java
│   ├── RecordingPacketHandler.java
│   ├── EntityTracker.java
│   └── TimelineBuilder.java
├── playback/                     (new — extracted from ReplaySession)
│   ├── PlaybackEngine.java
│   ├── ReplayBlockManager.java
│   └── ReplayInventoryUI.java
├── entity/                       (moved from root)
│   ├── RecordedEntity.java
│   ├── RecordedEntityFactory.java
│   ├── RecordedMob.java
│   └── RecordedPlayer.java
├── storage/                      (promoted from util/storage)
│   ├── ReplayStorage.java
│   ├── FileReplayStorage.java
│   ├── MySQLReplayStorage.java
│   └── MySQLConnectionManager.java
├── util/
│   ├── spawning/
│   │   ├── SpawnFakePlayer.java
│   │   └── SpawnFakeMob.java
│   ├── io/
│   │   ├── ItemStackSerializer.java
│   │   ├── ReplayCompressor.java
│   │   └── ReplayExporter.java
│   ├── EntityTypeMapper.java
│   ├── FloodgateHook.java
│   ├── ReplayCache.java
│   ├── ReplayObject.java
│   └── UpdateChecker.java
├── RecorderManager.java
├── RecordingSession.java         (slimmed — delegates to recording/ classes)
├── Replay.java
├── ReplayCommand.java
├── ReplayManagerImpl.java
├── ReplayRegistry.java
└── ReplaySession.java            (slimmed — delegates to playback/ classes)
```

---

## Testing Strategy

There are currently no tests in this project. The refactor should happen **before** investing in a test suite — not after. Here's why:

### Why tests before the refactor would be wasteful

The two largest classes (`RecordingSession`, `ReplaySession`) are tightly coupled to Bukkit events, PacketEvents packet listeners, and live server state. Writing unit tests against them today would require heavy mocking of `Player`, `World`, `PacketSendEvent`, and other framework types. The resulting tests would mostly verify mock wiring rather than real logic, and they would need to be largely rewritten once the refactor changes every class boundary. The effort-to-value ratio is poor.

### Why tests after the refactor will be far more useful

Once the megaclasses are split, the extracted components become naturally testable:

- A `TimelineBuilder` that accepts structured input and produces a timeline can be tested with plain data — no server mocking needed.
- An `EntityTracker` that manages a collection has a focused contract with clear inputs and outputs.
- A `ReplayBlockManager` with well-defined boundaries can be verified with simple unit tests and minimal mocking.

These smaller classes have **focused contracts** that make tests concise, meaningful, and durable. Tests written at this level catch real bugs instead of asserting that mocks were called in the right order.

### Recommended approach

1. **Before refactoring** — write a small number of high-level smoke tests through the public API (`ReplayManager` interface). Even basic "does it initialize, can I start/stop a recording" tests through `ReplayManagerImpl` will catch catastrophic breakage during the refactor without being throwaway work.
2. **Refactor (Tier 1)** — split the megaclasses.
3. **After refactoring** — write thorough unit tests against the new smaller classes, where each test targets a single responsibility and requires minimal setup.

The package moves in Tier 2 are mechanical and low-risk. The megaclass splits in Tier 1 are where the smoke tests provide the most safety for the least throwaway effort.

---

## Implementation Notes

- Each tier can be done independently. Tier 1 delivers the most value.
- Package moves (Tier 2) are mechanical but will touch every import statement in the affected files. Do them in a single commit to keep the diff reviewable.
- Typed timeline events (Tier 3) will require changes to both `RecordingSession` and `ReplaySession` simultaneously. Pair this with a format migration or maintain backward compatibility for existing saved replays.
- None of these changes affect the public API surface (`api/` package). External plugins will not need updates.
