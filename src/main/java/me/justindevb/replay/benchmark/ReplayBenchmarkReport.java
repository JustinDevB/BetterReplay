package me.justindevb.replay.benchmark;

import java.util.List;

public record ReplayBenchmarkReport(
        ReplayBenchmarkEnvironment environment,
        List<ReplayBenchmarkRun> runs
) {
}