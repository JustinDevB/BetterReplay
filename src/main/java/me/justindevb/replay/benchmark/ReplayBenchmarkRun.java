package me.justindevb.replay.benchmark;

public record ReplayBenchmarkRun(
        String preset,
        int players,
        int durationTicks,
        int eventCount,
        long archiveBytes,
        long payloadBytes,
        int iterations,
        long averageFinalizeNanos,
        long averageDecodeNanos,
        long averageExportNanos,
        long averageSeekBatchNanos,
        int seekOperations
) {
}