package me.justindevb.replay.benchmark;

public record ReplayBenchmarkEnvironment(
        String pluginVersion,
        String javaVersion,
        String osName,
        int availableProcessors,
        long maxMemoryBytes,
        String capturedAt
) {
}