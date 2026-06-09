# BetterReplay Commands

BetterReplay exposes one root command, `/replay`, with both normal player workflow commands and hidden operator utilities.

Registered command and permissions are declared in [plugin.yml](../src/main/resources/plugin.yml).

## Base command

- Root command: `/replay`
- Permission umbrella: `replay.*`
- Hidden utilities still live under the same root command

If a player runs `/replay` with no subcommand, BetterReplay prints a permission-filtered help list.

## Main workflow commands

| Command | Permission | Console | Notes |
|---|---|---|---|
| `/replay start <name> <player1 player2 ...> [seconds]` | `replay.start` | No | Starts a new recording. If the final token parses as an integer, it is treated as the duration in seconds. Omit it for an indefinite recording. |
| `/replay stop <name>` | `replay.stop` | No | Stops and saves an active recording session |
| `/replay play <name> [server:<server>]` | `replay.play` | No | Starts replay playback for the executing player. Optional `server:` routes the player to a different Velocity backend before playback starts |
| `/replay list [page]` | `replay.list` | No | Lists saved replays with clickable previous/next page controls |
| `/replay delete <name>` | `replay.delete` | No | Deletes a saved replay unless it is protected |
| `/replay protect <name>` | `replay.protect` | Yes | Protects a saved replay from deletion and retention cleanup |
| `/replay unprotect <name>` | `replay.unprotect` | Yes | Removes replay deletion protection |
| `/replay reload` | `replay.reload` | Yes | Reloads config, restarts retention when needed, and reports setting scopes |

Main workflow notes:

- `/replay start` uses a single-token recording name. The code reads the session name from the second argument directly.
- Commands that operate on saved replay names such as `stop`, `delete`, `protect`, and `unprotect` join the remaining arguments back into one name, so those saved replay names can contain spaces.
- `/replay play` currently reads the replay name from the next token only, then optionally accepts `server:<server>` after it. Example: `/replay play Test server:Replays`.
- The optional `server:` target is intended for Velocity setups where replay playback can be launched on another backend connected to the same BetterReplay MySQL database.
- `/replay list` page size and protected replay color come from `List.Page-Size` and `List.Protected-Highlight-Color`.
- Saved replay listings and replay-name tab completion use a shared 5-second cache; stale list reads refresh from the active storage backend and update the cache.
- `/replay delete` reports whether a replay was deleted, protected, or not found.

## Hidden operator utilities

These commands are not shown in the normal help output, but they are fully registered and permission-gated.

| Command | Permission | Console | Behavior |
|---|---|---|---|
| `/replay export <name> [player=<name\|all>] [start=<tick>] [end=<tick>]` | `replay.export` | Yes | Starts an asynchronous filtered export and prints the generated `.br` path when done |
| `/replay debug dump <name> [start=<tick>] [end=<tick>]` | `replay.debug` | Yes | Starts an asynchronous human-readable dump and prints the output path when done |
| `/replay debug info <name>` | `replay.debug` | Yes | Asynchronously prints metadata such as format, version, timestamps, counts, sizes, and chunk stats |
| `/replay benchmark run <small\|medium\|large\|all>` | `replay.benchmark` | Yes | Starts an asynchronous synthetic benchmark run and writes Markdown and JSON reports |
| `/replay benchmark last` | `replay.benchmark` | Yes | Prints the most recent benchmark report paths |

Hidden utility output locations:

- `/replay export` writes under the plugin `exports/` folder
- `/replay debug dump` writes under the plugin `dumps/` folder
- `/replay benchmark` writes Markdown and JSON reports under the plugin `benchmarks/` folder

## Filter and replay-name rules

The export and debug dump parsers follow the same rules:

- The replay name must appear before any `key=value` filters.
- Valid export filters are `player=`, `start=`, and `end=`.
- Valid debug dump filters are `start=` and `end=`.
- Tick filters must be non-negative integers.
- Replay names may contain spaces as long as all filters come after the full name.

`/replay debug info` does not accept filters.

## Reload behavior

`/replay reload` reinitializes config and reports changed settings in groups:

- Immediate changes that were applied live
- Retention settings that restarted the retention service
- Changes that only affect new recordings or replays
- Future-only changes such as update-check behavior
- Changes that still require restart

If no runtime-facing settings changed, BetterReplay reports that explicitly.

## Benchmark presets

`/replay benchmark run` accepts these presets:

| Preset | Players | Duration |
|---|---|---|
| `small` | `1` | `2400` ticks |
| `medium` | `4` | `12000` ticks |
| `large` | `12` | `36000` ticks |
| `all` | All of the above | Runs every preset |

For the benchmark metric definitions and report structure, see [BENCHMARKS.md](BENCHMARKS.md).

## Permissions

The current permission tree is:

- `replay.start`
- `replay.stop`
- `replay.play`
- `replay.list`
- `replay.delete`
- `replay.protect`
- `replay.unprotect`
- `replay.export`
- `replay.benchmark`
- `replay.debug`
- `replay.reload`
- `replay.*`

`replay.*` grants all of the above as children.

## Related documents

- [CONFIGURATION.md](CONFIGURATION.md)
- [ARCHITECTURE.md](ARCHITECTURE.md)
- [BENCHMARKS.md](BENCHMARKS.md)
