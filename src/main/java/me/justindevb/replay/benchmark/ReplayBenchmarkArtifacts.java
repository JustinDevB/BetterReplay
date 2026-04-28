package me.justindevb.replay.benchmark;

import java.nio.file.Path;

public record ReplayBenchmarkArtifacts(
        ReplayBenchmarkReport report,
        Path markdownPath,
        Path jsonPath
) {
}