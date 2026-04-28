# Benchmarks

BetterReplay includes a hidden admin diagnostic benchmark command that generates synthetic replay workloads and writes report output as both Markdown and JSON.

The sender needs the `replay.benchmark` permission.

## Commands

- `/replay benchmark run small`
- `/replay benchmark run medium`
- `/replay benchmark run large`
- `/replay benchmark run all`
- `/replay benchmark last`

`run` starts an asynchronous benchmark and writes output files under the plugin `benchmarks/` directory.

`last` prints the most recent Markdown and JSON report file paths recorded by the plugin process.

Only one benchmark run may execute at a time.

## Report structure

Each run in the JSON/Markdown report contains:

- `preset`: workload id, one of `small`, `medium`, or `large`
- `players`: number of synthetic players included in the workload
- `durationTicks`: replay duration in ticks
- `eventCount`: total synthetic timeline events generated before finalization
- `archiveBytes`: size in bytes of the finalized `.br` archive produced by the benchmark
- `payloadBytes`: estimated size in bytes of the decoded replay payload content after decompression
- `iterations`: number of measured iterations included in the average
- `averageFinalizeNanos`: average finalize time in nanoseconds
- `averageDecodeNanos`: average decode time in nanoseconds
- `averageExportNanos`: average export time in nanoseconds
- `averageSeekBatchNanos`: average batch seek time in nanoseconds
- `seekOperations`: number of indexed seek lookups performed inside the seek batch measurement

The harness performs `1` warmup iteration and `3` measured iterations for each preset.

## What is not being tested

The current benchmark is intentionally centered on offline storage-path work after a synthetic timeline has already been constructed in memory. It does not attempt to simulate the full real-time recording pipeline.

Specifically, it does not currently measure:

- append-log or temp-file writes during live recording
- async queueing or backpressure behavior between live event capture and background disk writes
- reading a recorder temp file back from disk before finalization
- opening a finalized replay archive from disk before decode
- Bukkit, Paper, PacketEvents, or entity-tracking overhead while a real server is actively recording players
- contention with world saves, logs, backups, database traffic, or other plugins sharing the same disk and CPU

One important exception is `Export`: that metric does create and write a real temp file for the filtered export archive. The other main metrics are measured from in-memory inputs.

## Metric definitions

### Finalize

`Finalize` measures how long it takes to turn the in-memory synthetic timeline into a finalized `.br` archive.

This includes the binary replay finalization path: event serialization, payload construction, compression, and archive packaging.

It does not include reading a recorder temp file from disk before finalization.

### Archive Bytes

`Archive bytes` is the size of the finalized `.br` archive produced by the benchmark.

This is the compressed saved-form size, including the archive container, manifest, and compressed replay payload.

### Payload Bytes

`Payload bytes` is an estimate of the decoded replay payload size after load and decompression.

This is intended to give a second size signal alongside `Archive bytes`, so you can compare stored size versus expanded replay content size. It is not the exact raw binary payload byte count from inside the archive; it is a decoded-content estimate derived from the loaded timeline.

### Decode

`Decode` measures how long it takes to load that finalized `.br` archive back into a decoded replay timeline.

In the current implementation this is the correct load-path metric to track. BetterReplay currently decompresses the whole replay payload during decode, so there is no meaningfully separate cheaper `open` stage.

It does not include first reading the replay archive from disk into memory.

### Export

`Export` measures how long it takes to build a filtered replay export from the decoded timeline.

The benchmark uses a fixed export query:

- player: `Player-1`
- start tick: `durationTicks / 4`
- end tick: `durationTicks / 2`

That means export timing covers filtering one named player over the middle half of the replay and then writing a new finalized `.br` archive for the filtered result.

### Seek Batch

`Seek Batch` measures the total time for a batch of indexed seek lookups against the decoded timeline.

The harness performs `100` calls to `findEventIndexAtOrAfterTick(...)`, spreading target ticks across the replay duration.

This is a batch cost, not a single seek cost. Approximate per-seek cost can be estimated as:

$$
\text{per-seek} \approx \frac{\text{Seek Batch}}{100}
$$

## Workload presets

The synthetic presets are deterministic and are intended to create comparable local measurements across runs.

### Small

- players: `1`
- duration: `2,400` ticks
- duration in seconds: about `120`

### Medium

- players: `4`
- duration: `12,000` ticks
- duration in seconds: about `600`

### Large

- players: `12`
- duration: `36,000` ticks
- duration in seconds: about `1,800`

## Event mix per synthetic player

For each synthetic player, the harness generates deterministic events across the replay duration:

- `PlayerMove` every `5` ticks
- `Swing` every `40` ticks
- `SprintToggle` every `100` ticks
- `SneakToggle` every `120` ticks
- `BlockBreak` every `200` ticks
- `BlockPlace` every `260` ticks
- `InventoryUpdate` every `400` ticks
- `PlayerQuit` at the final tick

This benchmark is designed to compare BetterReplay versions or configuration changes under similar local conditions. It is not intended to represent an exact live-server production profile.

## Interpreting results for live server impact

These benchmark numbers are not a direct estimate of "processing time per second" on your live server.

They are best interpreted as storage and replay workload timings for batch operations:

- `Finalize` helps estimate offline stop-and-save cost once a recording is being finalized
- `Decode` helps estimate offline replay-load cost when a saved replay is being opened for playback or tooling
- `Export` helps estimate the cost of building a filtered replay archive
- `Seek Batch` helps estimate indexed replay-navigation cost once the replay is already decoded

They do not tell you the full live recording cost per second for `50` real players, because that live cost also includes capture-side overhead that this benchmark does not model.

Examples of live costs not represented directly here:

- packet/event interception overhead
- per-tick object allocation and event construction
- thread handoff into async storage queues
- disk contention while the server is doing normal gameplay work
- scheduler overhead and interaction with other plugins

So if your goal is "What will 50 concurrent players do to my server in real time?", this benchmark alone is not enough to answer that precisely.

## What the benchmark is still useful for

Even with those limitations, the benchmark is still useful for several decisions:

- comparing BetterReplay versions on the same machine
- comparing config changes on the same machine
- estimating how replay save, load, export, and seek costs scale as replay size grows
- detecting regressions in archive finalization, decode, export, or indexed seek behavior

For example, if `Finalize` or `Decode` grows sharply as you increase players and duration, that is a signal that larger recordings will have more noticeable stop/save or load/playback costs, even if it is not the same thing as tick-time impact during recording.

## About 50-player testing

If you want to benchmark something closer to a 50-player target, the current presets still help as trend data, but they should be treated as scaling samples rather than a final live-capacity answer.

What you can learn safely from them:

- whether archive size growth looks roughly linear or starts to curve upward
- whether finalize and decode cost scale acceptably as workload size grows
- whether export and indexed seeks remain stable on much larger recordings

What you cannot conclude safely from them:

- exact tick-time impact during live recording of 50 players
- exact CPU percentage BetterReplay will consume while 50 players are online
- exact async disk-write pressure during a real session

If you need a realistic 50-player expectation, the better method is a two-part approach:

1. Use this benchmark to understand offline replay storage costs and scaling trends.
2. Run a live recording stress test on a dev server with synthetic or real player activity and observe TPS, CPU, memory, queue growth, and disk activity while recording is active.

That combination gives you a much more defensible picture than either approach alone.