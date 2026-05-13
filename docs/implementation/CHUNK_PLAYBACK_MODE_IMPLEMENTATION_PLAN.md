# Chunk Playback Mode Implementation Plan

This document converts the agreed chunk playback mode design into an implementation sequence for BetterReplay.

It reflects the final clarified behavior for the first pass:

- mode `1` keeps the current moving replay chunk window behavior
- mode `2` still loads replay chunks using the same replay-configured playback radius, but does not proactively restore or unload chunks as they leave that window during playback
- in mode `2`, replay chunks remain replay-owned until replay teardown, but BetterReplay must still handle chunks that unload naturally on the client and resend them if the viewer later returns
- in mode `2`, teardown restores only replay-owned chunks that are still believed to be resident on the client

## Goal

Add two numbered playback chunk modes that preserve the current replay chunk loading radius while allowing an alternative mode that defers chunk restore and unload work until replay end.

## Non-Goals For The First Pass

- No native client view distance driven loading behavior
- No third mode beyond the two numbered modes
- No change to replay chunk prioritization within the configured playback radius
- No change to chunk recording radius or recording-side chunk capture behavior

## Frozen Decisions

The following choices are fixed for the first implementation pass:

- The config exposes exactly two integer modes: `1` and `2`.
- Mode `1` is the current behavior.
- Mode `2` uses the same replay playback radius for loading as mode `1`.
- Mode `2` does not proactively restore replay chunks when they leave the desired playback window during an active replay.
- Mode `2` may still observe that a replay-owned chunk is no longer client-resident if Paper reports it as unsent.
- If a replay-owned chunk becomes desired again after a natural unload, BetterReplay resends the replay chunk payload.
- Mode `2` restores replay-owned chunks only when the replay ends, and only if they are still believed to be client-resident.
- The existing paced teardown restore path remains the restore mechanism for deferred mode `2` cleanup.
- Invalid configured mode values fall back to mode `1`.
- The existing `Playback.Chunk-View-Radius` setting remains the single chunk load radius control for both modes.

## Recommended Delivery Strategy

Implement this as a narrow vertical slice in the following order:

1. Add the config contract for the new mode.
2. Refactor playback state so chunk visibility and chunk restore ownership are no longer represented by a single set.
3. Preserve mode `1` behavior exactly.
4. Add mode `2` deferred-unload behavior.
5. Integrate deferred cleanup with replay stop and disconnect teardown.
6. Add regression coverage and diagnostics.

This order keeps the highest-risk behavior change isolated to `ReplayBlockManager` while preserving the existing replay packet loading path.

## Behavioral Contract

### Mode `1`

Current moving-window behavior:

- compute desired chunks from the viewer location and configured playback radius
- prepare and send replay chunks as they enter the desired set
- queue live chunk restore when replay chunks leave the desired set
- pace replay load and restore work using the existing BRCP scheduling and teardown logic

### Mode `2`

Deferred restore behavior:

- compute desired chunks from the viewer location and configured playback radius
- prepare and send replay chunks as they first enter the desired set
- do not proactively restore replay chunks when they later leave the desired set during the replay
- keep those replay chunks tracked as replay-owned until replay stop
- if the client later no longer has one of those chunks because it unloaded naturally, mark it as not currently resident
- if the viewer later re-enters a naturally unloaded replay-owned chunk, resend the replay chunk payload
- when the replay stops, restore only replay-owned chunks that are still believed to be client-resident using the existing stop-time paced restore flow

### Important Implication

Mode `2` intentionally allows the set of replay-owned chunks to grow over the lifetime of the session. The total stop-time restore workload therefore becomes proportional to the subset of those chunks still believed to be resident on the client when stop is pressed, not only the chunks still near the viewer when stop is pressed.

## Natural Unload Contract

Mode `2` must distinguish between replay ownership and client residency.

- Replay ownership answers whether BetterReplay is still responsible for the chunk if the replay ends.
- Client residency answers whether the client currently still has the replay chunk loaded.
- A replay-owned chunk can stop being client-resident if the player moves far enough away that normal server chunk tracking unloads it.
- When that happens, BetterReplay should not eagerly restore live world data for that chunk because there is nothing replay-mutated left on the client to overwrite.
- If the viewer later comes back and the chunk becomes desired again, BetterReplay must send the replay chunk again because the prior client copy is gone.

Paper's sent-chunk APIs are sufficient for this first pass:

- `Player.isChunkSent(long chunkKey)` for cheap spot checks
- `Player.getSentChunkKeys()` for broader reconciliation when needed

This is not a native-distance ownership model. BetterReplay still chooses replay loads from `Playback.Chunk-View-Radius`; the sent-chunk APIs are only used to decide whether a replay-owned chunk is still present on the client and therefore whether it needs resend or teardown restore work.

## Ownership Boundaries

### `ReplayConfigSetting`

Owns the new config key and its documentation comment.

Responsibilities:

- add the numbered playback chunk mode setting
- preserve `Playback.Chunk-View-Radius` as the active load radius control

### `ReplayConfigManager`

Owns config backfill and value sanitation.

Responsibilities:

- add the new setting to generated configs
- backfill comments into older configs
- clamp unsupported values to mode `1`

### `ReplayBlockManager`

Owns playback-time chunk selection, replay chunk application, and restore policy.

Responsibilities:

- resolve the configured chunk mode once at construction
- keep mode `1` behavior unchanged
- defer chunk restore in mode `2`
- reconcile replay ownership against Paper sent-chunk state in mode `2`
- resend replay chunks on re-entry when a replay-owned chunk is no longer client-resident
- ensure stop-time baseline restoration includes only replay-owned chunks still resident on the client in mode `2`
- keep diagnostics understandable when deferred chunks accumulate

### Replay teardown path

The existing stop path in `ReplaySession` and the paced restore drain in `ReplayBlockManager` remain the cleanup mechanism.

Responsibilities:

- restore only replay-owned chunks still resident on the client on replay end
- cancel safely if the player disconnects during teardown
- avoid re-restoring the same chunk more than once

## Data And State Model Changes

## Why A State Refactor Is Required

The current implementation largely uses one conceptual chunk set for both:

- chunks that are inside the current desired playback window
- chunks that have replay state active on the viewer and may need later restoration

That is sufficient for mode `1`, but it is not sufficient for mode `2`.

Mode `2` needs to preserve replay-modified chunks even after they leave the active desired window, which means those two meanings must be split.

## Recommended Internal State Split

Recommended playback state concepts inside `ReplayBlockManager`:

- `desiredChunks`: chunks currently inside the moving playback radius
- `replayOwnedChunks`: chunks whose replay baseline has been applied to the viewer and has not yet been restored
- `clientResidentReplayChunks`: replay-owned chunks still believed to be present on the client
- `deferredRestoreChunks`: replay-owned chunks that are still client-resident and therefore still need stop-time restoration in mode `2`

Expected semantics:

- in mode `1`, a chunk leaving `desiredChunks` is queued for immediate restore and removed from replay ownership after restoration
- in mode `2`, a chunk leaving `desiredChunks` remains replay-owned even if it is no longer currently desired
- in mode `2`, if Paper reports that a replay-owned chunk is no longer sent to the player, remove it from `clientResidentReplayChunks` and from any stop-time restore queue membership
- in mode `2`, if a no-longer-resident replay-owned chunk becomes desired again, resend the replay payload and mark it client-resident again

The exact field names can vary, but the implementation should no longer overload a single set to represent both visibility and restore ownership.

## Config Contract

### New Setting

Recommended key:

- `Playback.Chunk-Mode`

Recommended values:

- `1`: moving-window replay chunk restore behavior
- `2`: deferred replay chunk restore until replay stop

Recommended comment:

- `Chunk playback mode: 1 = restore chunks as they leave the replay view radius, 2 = keep replay ownership until replay end, resend replay chunks if they unload naturally, and restore only still-loaded replay chunks during teardown.`

### Existing Setting Semantics

`Playback.Chunk-View-Radius` remains unchanged:

- mode `1`: load radius
- mode `2`: load radius

This avoids introducing a second radius control and keeps the user-facing mental model simple.

## Phase 1: Config And Validation

Deliverables:

- new playback chunk mode config setting
- comment backfill for existing configs
- invalid mode values coerced to `1`

Implementation tasks:

- add the new enum entry in `ReplayConfigSetting`
- bump `ReplayConfigManager` config version
- backfill the new key into existing configs
- clamp unsupported values to mode `1`

Touch points:

- `src/main/java/me/justindevb/replay/config/ReplayConfigSetting.java`
- `src/main/java/me/justindevb/replay/config/ReplayConfigManager.java`

Tests:

- config initialization writes the new key
- migration adds the new key to legacy configs
- invalid mode values are rewritten to `1`

Exit criteria:

- the plugin always starts with a valid mode value and documented config entry

## Phase 2: Internal Playback Mode Abstraction

Deliverables:

- a small internal representation for chunk playback mode

Implementation tasks:

- add a tiny enum or equivalent parser for mode `1` and mode `2`
- resolve the configured mode once in `ReplayBlockManager`
- remove raw integer branching from the main refresh flow where practical

Recommended shape:

```java
enum PlaybackChunkMode {
    MOVING_WINDOW,
    DEFERRED_UNLOAD_UNTIL_STOP
}
```

Exit criteria:

- mode selection is explicit and readable in playback code

## Phase 3: Refactor Chunk State Ownership

Deliverables:

- playback state can distinguish desired visibility from eventual cleanup ownership

Implementation tasks:

- identify the current responsibilities of `renderedChunks`, `queuedLiveChunkRestores`, and related caches
- split the state model so that a chunk can be outside the active desired radius while still being replay-owned for later teardown cleanup
- add a narrow client-residency reconciliation step for replay-owned chunks in mode `2` using `Player.isChunkSent(long)` or equivalent
- preserve cache reuse for replay packet preparation
- ensure re-entry into an already client-resident replay chunk does not resend work unnecessarily
- ensure re-entry after natural unload does resend replay chunk data

Touch points:

- `src/main/java/me/justindevb/replay/playback/ReplayBlockManager.java`

Exit criteria:

- a chunk can safely leave the moving desired set without being immediately restored when mode `2` is active, while still being resent correctly after natural unload

## Phase 4: Preserve Mode `1` Behavior

Deliverables:

- explicit mode `1` branch with behavior matching the current implementation

Implementation tasks:

- keep `refreshVisibleChunkBaselines()` semantics unchanged for mode `1`
- keep live restore queueing on desired-set exit
- keep existing replay load prioritization and in-flight prepare limits
- verify no behavioral regression in stop-time teardown for mode `1`

Exit criteria:

- all current chunk playback tests continue to pass under mode `1`

## Phase 5: Implement Mode `2` Deferred Restore

Deliverables:

- replay chunks are still loaded when entering the configured replay radius
- replay chunks are not restored when leaving that radius during playback

Implementation tasks:

- change the center-change unload handling so mode `2` does not call `queueLiveChunkRestore()` for chunks leaving the desired window during playback
- retain those chunks in a stop-time cleanup set
- ensure replay chunks are only prepared and sent the first time they are needed, not every time the viewer moves near them again
- make sure stale replay prepare cancellation still only targets chunks that have never been applied, not chunks intentionally retained for deferred restore

Expected behavior details:

- moving away from a replay chunk in mode `2` should do no live restore work during the replay
- revisiting a previously loaded replay chunk in mode `2` should not require a resend if it is still considered replay-active
- stop-time teardown should restore all deferred chunks, even if they are no longer near the viewer

Exit criteria:

- active playback chunk exit work in mode `2` is reduced to bookkeeping only

## Phase 6: Teardown Integration

Deliverables:

- mode `2` deferred chunks are restored when the replay ends

Implementation tasks:

- seed replay stop cleanup from the full deferred replay chunk set in mode `2`
- reuse the existing paced BRCP drain where available
- preserve the current offline-viewer guard and task cancellation behavior
- deduplicate chunks so teardown does not restore the same chunk multiple times

Important consideration:

Mode `2` can make replay stop substantially heavier because the cleanup scope is the full session footprint. The current paced drain already moves in the right direction, but this implementation should assume that teardown pacing matters even more in mode `2` than it does now.

Exit criteria:

- replay end performs eventual cleanup for all deferred chunks without reintroducing large synchronous restore spikes

## Phase 7: Diagnostics

Deliverables:

- chunk timing logs remain understandable under both modes

Implementation tasks:

- extend `logChunkRefreshTimings()` to include active mode
- add counts for deferred chunks retained in mode `2`
- log stop-time restore queue size when teardown begins
- keep the existing load and live-restore timing fields intact for mode `1`

Recommended additional log fields:

- `mode`
- `deferredReplayChunks`
- `teardownRestoreQueueSize`

Exit criteria:

- performance debugging can distinguish moving-window restore work from deferred stop-time cleanup work

## Phase 8: Regression Coverage

Deliverables:

- tests that pin both mode behaviors

Primary test file:

- `src/test/java/me/justindevb/replay/playback/ReplayBlockManagerTest.java`

Config test file:

- `src/test/java/me/justindevb/replay/config/ReplayConfigManagerTest.java`

Required playback tests:

- mode `1` still restores real world state when a chunk leaves the playback window
- mode `2` does not queue live restore when a chunk leaves the playback window
- mode `2` restores deferred chunks on `restoreSessionBaseline()`
- mode `2` re-entry into a previously loaded replay chunk does not duplicate sends unnecessarily
- teardown pacing still applies when mode `2` accumulates multiple deferred chunks
- disconnect during mode `2` teardown still cancels cleanly

Required config tests:

- generated config includes the new mode key and comment
- migrated config receives the new key
- invalid mode values are sanitized

Exit criteria:

- both numbered modes are behaviorally pinned by deterministic tests

## Phase 9: Documentation Updates When Code Ships

Deliverables:

- user-facing config documentation for the new mode
- changelog entry for the new behavior

Implementation tasks:

- update `README.md` with the new playback chunk mode setting and semantics
- add a `CHANGELOG.md` entry under `Unreleased`

`docs/API.md` is not expected to change unless this mode is later surfaced through the public API instead of config only.

## Recommended Delivery Order

1. Config setting and config migration.
2. Internal mode enum or parser.
3. ReplayBlockManager state split.
4. Mode `1` parity pass.
5. Mode `2` deferred restore behavior.
6. Stop-time teardown integration.
7. Diagnostics.
8. Tests.
9. README and CHANGELOG updates when implementation begins.

## Acceptance Criteria

The implementation is complete when all of the following are true:

- mode `1` behaves exactly like the current release behavior
- mode `2` loads replay chunks using the same configured playback radius as mode `1`
- mode `2` performs no per-exit live chunk restore while the replay is active
- mode `2` restores all replay-modified chunks during replay teardown
- stop-time cleanup remains paced and safe when the viewer disconnects
- regression tests cover both modes and config validation