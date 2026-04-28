package me.justindevb.replay.benchmark;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ReplayBenchmarkService {

    private final ReplayBenchmarkHarness harness;
    private final ReplayBenchmarkReportWriter writer;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ReplayBenchmarkArtifacts lastArtifacts;

    public ReplayBenchmarkService(
                                  ReplayBenchmarkHarness harness,
                                  ReplayBenchmarkReportWriter writer,
                                  Executor executor) {
        this.harness = Objects.requireNonNull(harness, "harness");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public boolean isRunning() {
        return running.get();
    }

    public Optional<ReplayBenchmarkArtifacts> lastArtifacts() {
        return Optional.ofNullable(lastArtifacts);
    }

    public CompletableFuture<ReplayBenchmarkArtifacts> startPreset(ReplayBenchmarkPreset preset) {
        return start(preset.id(), List.of(preset));
    }

    public CompletableFuture<ReplayBenchmarkArtifacts> startAll() {
        return start("all", List.of(ReplayBenchmarkPreset.values()));
    }

    private CompletableFuture<ReplayBenchmarkArtifacts> start(String label, List<ReplayBenchmarkPreset> presets) {
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("A replay benchmark is already running"));
        }

        CompletableFuture<ReplayBenchmarkArtifacts> future = CompletableFuture.supplyAsync(() -> {
            try {
                ReplayBenchmarkReport report = harness.run(presets);
                ReplayBenchmarkArtifacts artifacts = writer.write(label, report);
                lastArtifacts = artifacts;
                return artifacts;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to write replay benchmark report", e);
            }
        }, executor);

        return future.whenComplete((ignored, throwable) -> running.set(false));
    }
}