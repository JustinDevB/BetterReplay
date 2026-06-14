# BetterReplay Configuration

BetterReplay uses typed configuration keys defined in [ReplayConfigSetting.java](../src/main/java/me/justindevb/replay/config/ReplayConfigSetting.java) and migrated through [ReplayConfigManager.java](../src/main/java/me/justindevb/replay/config/ReplayConfigManager.java).

This document covers the supported keys, defaults, valid values, and runtime reload behavior.

## Reload model

`/replay reload` does not treat every setting the same way. Each key has an explicit reload scope.

| Reload scope | Meaning |
|---|---|
| `IMMEDIATE` | Applied immediately to the running server after `/replay reload` |
| `RETENTION_RESTART` | Applied by restarting the retention service during reload |
| `NEW_SESSIONS_ONLY` | Used only by recordings or replays started after the reload |
| `RESTART_REQUIRED` | Requires a plugin/server restart to take effect |
| `FUTURE_ONLY` | Affects future startup or manual checks rather than current sessions |
| `INTERNAL` | Internal migration bookkeeping; not intended for operators |

In practice:

- Viewer safety, viewer restore, vanish, Velocity default replay-server routing, list formatting, and similar live-read settings are immediate.
- Retention settings restart the retention service.
- Playback speed and chunk capture/playback settings affect newly started sessions only.
- Storage backend and MySQL connection changes still require restart.

## Minimal examples

### File storage

```yaml
General:
  Check-Update: true
  Storage-Type: file
```

### MySQL storage

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

### Playback, list, and retention examples

```yaml
Playback:
  Speed-Step: 0.2
  Max-Speed: 1.0
  Viewer-Safety-Mode: creative
  Vanish-Viewer: true
  Restore-Viewer-Location-On-Stop: true
  Restore-Viewer-GameMode-On-Stop: true
  Restore-Viewer-Flight-On-Stop: true
  Restore-Viewer-State-On-Rejoin: true
  Chunk-Mode: 1
  Chunk-View-Radius: 3
  Chunk-Send-Limit-Per-Tick: 1
  Chunk-Clear-Limit-Per-Tick: 1
  Chunk-Timing-Diagnostics: false

Recording:
  Chunk-Capture:
    Enabled: false
    Radius: 1
    Capture-Interval-Ticks: 20
    Max-Unique-Chunks-Per-Recording: 20000

Retention:
  Enabled: false
  Max-Age: 30d
  Check-Interval: 1h
  Delete-Partial-Failures: false
  Log-Deletions: true

List:
  Page-Size: 10
  Protected-Highlight-Color: "&6"

Velocity:
  Default-Replay-Server: ""
```

## Settings reference

### Internal

| Key | Default | Reload scope | Notes |
|---|---|---|---|
| `Config-Version` | `8` | `INTERNAL` | Internal migration version written by the plugin |

### General

| Key | Default | Reload scope | Notes |
|---|---|---|---|
| `General.Check-Update` | `true` | `FUTURE_ONLY` | Enables update checks on startup |
| `General.Storage-Type` | `file` | `RESTART_REQUIRED` | Valid values are `file` and `mysql` |
| `General.MySQL.host` | `host` | `RESTART_REQUIRED` | MySQL hostname or IP |
| `General.MySQL.port` | `3306` | `RESTART_REQUIRED` | MySQL port |
| `General.MySQL.database` | `database` | `RESTART_REQUIRED` | Database or schema name |
| `General.MySQL.user` | `username` | `RESTART_REQUIRED` | MySQL user |
| `General.MySQL.password` | `password` | `RESTART_REQUIRED` | MySQL password |

General notes:

- `General.Storage-Type` should be lowercase.
- If the storage type is invalid, BetterReplay logs the problem and falls back to file storage.
- MySQL replay names are stored in a `VARCHAR(64)` primary key column.
- Binary replay payloads require the `data` column to be `LONGBLOB`; BetterReplay widens it during storage initialization when needed.

### Recording

#### `Recording.Chunk-Capture`

| Key | Default | Reload scope | Notes |
|---|---|---|---|
| `Recording.Chunk-Capture.Enabled` | `false` | `NEW_SESSIONS_ONLY` | Enables chunk baseline capture for binary `.br` replays |
| `Recording.Chunk-Capture.Radius` | `1` | `NEW_SESSIONS_ONLY` | Chunk radius around each tracked player |
| `Recording.Chunk-Capture.Capture-Interval-Ticks` | `20` | `NEW_SESSIONS_ONLY` | How often chunk interest is recomputed |
| `Recording.Chunk-Capture.Max-Unique-Chunks-Per-Recording` | `20000` | `NEW_SESSIONS_ONLY` | Upper bound for unique captured chunks per recording |

Recording notes:

- Chunk capture is stored only in finalized binary `.br` archives.
- Legacy JSON replays remain timeline-only.
- When the unique-chunk cap is reached, recording continues but additional chunk baselines are skipped.

### Playback

| Key | Default | Reload scope | Notes |
|---|---|---|---|
| `Playback.Speed-Step` | `0.2` | `NEW_SESSIONS_ONLY` | Speed increment used by the faster/slower controls |
| `Playback.Max-Speed` | `1.0` | `NEW_SESSIONS_ONLY` | Upper playback speed bound; enforced to a minimum of `1.0` |
| `Playback.Viewer-Safety-Mode` | `creative` | `IMMEDIATE` | Valid values are `creative` and `off` |
| `Playback.Vanish-Viewer` | `true` | `IMMEDIATE` | Hides replay viewers from live players while playback is active |
| `Playback.Restore-Viewer-Location-On-Stop` | `true` | `IMMEDIATE` | Restores the viewer's original location after replay stop |
| `Playback.Restore-Viewer-GameMode-On-Stop` | `true` | `IMMEDIATE` | Restores the viewer's original game mode after replay stop |
| `Playback.Restore-Viewer-Flight-On-Stop` | `true` | `IMMEDIATE` | Restores `allowFlight` and `flying` state after replay stop |
| `Playback.Restore-Viewer-State-On-Rejoin` | `true` | `IMMEDIATE` | Restores saved viewer state after disconnect/rejoin during replay |
| `Playback.Chunk-Mode` | `1` | `NEW_SESSIONS_ONLY` | Replay chunk teardown mode |
| `Playback.Chunk-View-Radius` | `3` | `NEW_SESSIONS_ONLY` | Chunk radius around the replay viewer |
| `Playback.Chunk-Send-Limit-Per-Tick` | `1` | `NEW_SESSIONS_ONLY` | Replay chunk send rate limit per tick |
| `Playback.Chunk-Clear-Limit-Per-Tick` | `1` | `NEW_SESSIONS_ONLY` | Live chunk restore rate limit per tick |
| `Playback.Chunk-Timing-Diagnostics` | `false` | `NEW_SESSIONS_ONLY` | Enables detailed chunk timing logs |

Playback notes:

- `Playback.Viewer-Safety-Mode=creative` temporarily moves the viewer into creative mode before replay teleportation. `off` preserves the current mode.
- `Playback.Vanish-Viewer=true` is recommended on live servers so moderator teleports are not visible inside active builds.
- `Playback.Chunk-Mode=1` restores live chunks as they leave the moving replay window.
- `Playback.Chunk-Mode=2` defers live chunk restoration until replay stop, allows natural unloads, and resends replay chunks if the viewer returns after a natural unload.
- `Playback.Chunk-Send-Limit-Per-Tick` and `Playback.Chunk-Clear-Limit-Per-Tick` default to conservative values for mixed live servers.
- Replay-load probing runs ahead of chunk sends at `10x` the configured send rate so missing replay chunks do not bottleneck playback.
- If `Playback.Chunk-View-Radius` is larger than `Recording.Chunk-Capture.Radius`, only chunks actually captured during recording can be replayed; uncaptured chunks remain live.

### Velocity

| Key | Default | Reload scope | Notes |
|---|---|---|---|
| `Velocity.Default-Replay-Server` | `""` | `IMMEDIATE` | Default backend used by `/replay play <name>` when no `server:<server>` argument is provided |

Velocity notes:

- Leave `Velocity.Default-Replay-Server` blank to keep the current behavior: `/replay play <name>` starts playback on the current server unless the command includes `server:<server>`.
- Set `Velocity.Default-Replay-Server` to a backend name such as `replays` to route ordinary `/replay play <name>` requests through Velocity automatically.
- An explicit `/replay play <name> server:<server>` target overrides the configured default for that command run.
- If the transfer request cannot be sent, the proxy reports a transfer failure, or the proxy does not respond, the viewer receives a chat message explaining that BetterReplay could not connect to the replay server.

### Retention

| Key | Default | Reload scope | Notes |
|---|---|---|---|
| `Retention.Enabled` | `false` | `RETENTION_RESTART` | Enables scheduled cleanup of old replays |
| `Retention.Max-Age` | `30d` | `RETENTION_RESTART` | Replay age threshold before cleanup eligibility |
| `Retention.Check-Interval` | `1h` | `RETENTION_RESTART` | How often retention scans for expired replays |
| `Retention.Delete-Partial-Failures` | `false` | `RETENTION_RESTART` | Whether to continue deleting after an earlier failure |
| `Retention.Log-Deletions` | `true` | `RETENTION_RESTART` | Logs each successful retention deletion |

Retention notes:

- Duration values accept `s`, `m`, `h`, and `d` suffixes.
- Protected replays are skipped by both retention cleanup and manual delete commands until they are explicitly unprotected.
- Protection metadata records `protectedAt` and `protectedBy`.

### List

| Key | Default | Reload scope | Notes |
|---|---|---|---|
| `List.Page-Size` | `10` | `IMMEDIATE` | Number of saved replays shown per `/replay list` page |
| `List.Protected-Highlight-Color` | `&6` | `IMMEDIATE` | Chat color code used to highlight protected replays |

List notes:

- Protected replays are highlighted in `/replay list` using the configured color.
- The default `&6` resolves to gold.
- Older config files using `list-page-size` and `list-protected-highlight-color` are migrated automatically.

## Runtime reload behavior

`/replay reload` performs all of the following:

- Re-reads `config.yml`
- Restarts retention scheduling when retention settings changed
- Applies immediate settings directly to the running server
- Reports which changed settings affect only new sessions
- Reports which changed settings still require restart

It does not hot-swap the active storage backend or reconnect MySQL under a new configuration without restart.

## Operational notes

- New recordings should stay on finalized binary `.br` archives.
- Legacy JSON support exists for compatibility only and is planned for removal in a later version.
- Older alpha `.br` archives that predate format `v2` are intentionally unsupported.
- `/replay benchmark` is always permission-gated through `replay.benchmark`; the old `General.Enable-Benchmark-Command` toggle has been removed.

## Related documents

- [COMMANDS.md](COMMANDS.md)
- [ARCHITECTURE.md](ARCHITECTURE.md)
- [BINARY_FORMAT_SPEC.md](BINARY_FORMAT_SPEC.md)
- [ARCHIVE_MANIFEST_SCHEMA.md](ARCHIVE_MANIFEST_SCHEMA.md)
