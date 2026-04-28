package me.justindevb.replay.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class ReplayBenchmarkReportWriter {

    private final Path outputDir;
    private final Gson gson;

    public ReplayBenchmarkReportWriter(Path outputDir) {
        this(outputDir, new GsonBuilder().setPrettyPrinting().create());
    }

    ReplayBenchmarkReportWriter(Path outputDir, Gson gson) {
        this.outputDir = outputDir;
        this.gson = gson;
    }

    public ReplayBenchmarkArtifacts write(String label, ReplayBenchmarkReport report) throws IOException {
        Files.createDirectories(outputDir);
        String baseName = sanitizeFileToken(report.environment().capturedAt()) + "-" + label.toLowerCase(Locale.ROOT);
        Path markdownPath = outputDir.resolve(baseName + ".md");
        Path jsonPath = outputDir.resolve(baseName + ".json");

        Files.writeString(jsonPath, gson.toJson(report), StandardCharsets.UTF_8);
        Files.writeString(markdownPath, toMarkdown(report), StandardCharsets.UTF_8);
        return new ReplayBenchmarkArtifacts(report, markdownPath, jsonPath);
    }

    private static String toMarkdown(ReplayBenchmarkReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# BetterReplay Benchmark Report\n\n");
        builder.append("Captured: ").append(report.environment().capturedAt()).append("\n\n");
        builder.append("## Environment\n\n");
        builder.append("- Plugin version: ").append(report.environment().pluginVersion()).append("\n");
        builder.append("- Java version: ").append(report.environment().javaVersion()).append("\n");
        builder.append("- OS: ").append(report.environment().osName()).append("\n");
        builder.append("- Available processors: ").append(report.environment().availableProcessors()).append("\n");
        builder.append("- Max memory (MiB): ").append(report.environment().maxMemoryBytes() / (1024 * 1024)).append("\n\n");
        builder.append("## Runs\n\n");
        builder.append("| Preset | Players | Duration (ticks) | Events | Archive bytes | Payload bytes | Finalize ms | Decode ms | Export ms | Seek batch ms |\n");
        builder.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ReplayBenchmarkRun run : report.runs()) {
            builder.append('|').append(' ').append(run.preset())
                    .append(" | ").append(run.players())
                    .append(" | ").append(run.durationTicks())
                    .append(" | ").append(run.eventCount())
                    .append(" | ").append(run.archiveBytes())
                .append(" | ").append(run.payloadBytes())
                    .append(" | ").append(toMillis(run.averageFinalizeNanos()))
                    .append(" | ").append(toMillis(run.averageDecodeNanos()))
                    .append(" | ").append(toMillis(run.averageExportNanos()))
                    .append(" | ").append(toMillis(run.averageSeekBatchNanos()))
                    .append(" |\n");
        }
        builder.append("\n");
        builder.append("Results were collected on a running server. Compare runs only when server load and environment are similar.\n");
        return builder.toString();
    }

    private static String toMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static String sanitizeFileToken(String value) {
        return value.replace(':', '-');
    }
}