package me.justindevb.replay.benchmark;

import me.justindevb.replay.api.ReplayExportQuery;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.ReplayExporter;
import me.justindevb.replay.storage.ReplayIndexedTimeline;
import me.justindevb.replay.storage.binary.BinaryReplayStorageCodec;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ReplayBenchmarkHarness {

    private static final int WARMUP_ITERATIONS = 1;
    private static final int MEASURE_ITERATIONS = 3;
    private static final int SEEK_OPERATIONS = 100;

    private final BinaryReplayStorageCodec codec;
    private final ReplayExporter exporter;
    private final String pluginVersion;

    public ReplayBenchmarkHarness(String pluginVersion) {
        this(new BinaryReplayStorageCodec(), new ReplayExporter(), pluginVersion);
    }

    ReplayBenchmarkHarness(BinaryReplayStorageCodec codec, ReplayExporter exporter, String pluginVersion) {
        this.codec = codec;
        this.exporter = exporter;
        this.pluginVersion = pluginVersion;
    }

    public ReplayBenchmarkReport run(List<ReplayBenchmarkPreset> presets) {
        ReplayBenchmarkEnvironment environment = new ReplayBenchmarkEnvironment(
                pluginVersion,
                System.getProperty("java.version", "unknown"),
                System.getProperty("os.name", "unknown"),
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory(),
            Instant.now().toString());

        List<ReplayBenchmarkRun> runs = presets.stream()
                .map(this::runPreset)
                .toList();
        return new ReplayBenchmarkReport(environment, runs);
    }

    private ReplayBenchmarkRun runPreset(ReplayBenchmarkPreset preset) {
        List<TimelineEvent> timeline = buildTimeline(preset);
        long finalizeTotal = 0;
        long decodeTotal = 0;
        long exportTotal = 0;
        long seekTotal = 0;
        long archiveBytes = 0;
        long payloadBytes = 0;

        for (int iteration = 0; iteration < WARMUP_ITERATIONS + MEASURE_ITERATIONS; iteration++) {
            MeasuredRun measuredRun = measureRun(preset, timeline);
            if (iteration >= WARMUP_ITERATIONS) {
                finalizeTotal += measuredRun.finalizeNanos();
                decodeTotal += measuredRun.decodeNanos();
                exportTotal += measuredRun.exportNanos();
                seekTotal += measuredRun.seekBatchNanos();
                archiveBytes = measuredRun.archiveBytes();
                payloadBytes = measuredRun.payloadBytes();
            }
        }

        return new ReplayBenchmarkRun(
                preset.id(),
                preset.players(),
                preset.durationTicks(),
                timeline.size(),
                archiveBytes,
                payloadBytes,
                MEASURE_ITERATIONS,
                finalizeTotal / MEASURE_ITERATIONS,
                decodeTotal / MEASURE_ITERATIONS,
                exportTotal / MEASURE_ITERATIONS,
                seekTotal / MEASURE_ITERATIONS,
                SEEK_OPERATIONS);
    }

    private MeasuredRun measureRun(ReplayBenchmarkPreset preset, List<TimelineEvent> timeline) {
        try {
            long start = System.nanoTime();
            byte[] archive = codec.finalizeReplay(preset.id(), timeline, pluginVersion);
            long finalizeNanos = System.nanoTime() - start;

            start = System.nanoTime();
            List<TimelineEvent> decodedTimeline = codec.decodeTimeline(archive, pluginVersion);
            long decodeNanos = System.nanoTime() - start;
            long payloadBytes = estimatePayloadBytes(decodedTimeline);

            ReplayExportQuery query = new ReplayExportQuery("Player-1", preset.durationTicks() / 4, preset.durationTicks() / 2);
            start = System.nanoTime();
            File export = exporter.exportReplay(preset.id(), decodedTimeline, query, pluginVersion);
            long exportNanos = System.nanoTime() - start;
            if (export.exists()) {
                export.delete();
            }

            start = System.nanoTime();
            if (decodedTimeline instanceof ReplayIndexedTimeline indexedTimeline) {
                for (int seek = 0; seek < SEEK_OPERATIONS; seek++) {
                    int targetTick = (preset.durationTicks() * seek) / SEEK_OPERATIONS;
                    indexedTimeline.findEventIndexAtOrAfterTick(targetTick);
                }
            }
            long seekBatchNanos = System.nanoTime() - start;

            return new MeasuredRun(archive.length, payloadBytes, finalizeNanos, decodeNanos, exportNanos, seekBatchNanos);
        } catch (IOException e) {
            throw new IllegalStateException("Benchmark run failed for preset " + preset.id(), e);
        }
    }

    private static long estimatePayloadBytes(List<TimelineEvent> decodedTimeline) {
        long total = 0;
        for (TimelineEvent event : decodedTimeline) {
            total += event.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        return total;
    }

    private static List<TimelineEvent> buildTimeline(ReplayBenchmarkPreset preset) {
        List<TimelineEvent> timeline = new ArrayList<>();
        for (int playerIndex = 1; playerIndex <= preset.players(); playerIndex++) {
            String uuid = "uuid-" + playerIndex;
            String name = "Player-" + playerIndex;
            for (int tick = 0; tick <= preset.durationTicks(); tick++) {
                if (tick % 5 == 0) {
                    timeline.add(new TimelineEvent.PlayerMove(tick, uuid, name, "world",
                            playerIndex * 10 + (tick / 10.0), 64 + (playerIndex % 3), playerIndex * 5 + (tick / 15.0),
                            tick % 360, (tick % 90) - 45, "STANDING"));
                }
                if (tick > 0 && tick % 40 == 0) {
                    timeline.add(new TimelineEvent.Swing(tick, uuid, (tick / 40) % 2 == 0 ? "MAIN_HAND" : "OFF_HAND"));
                }
                if (tick > 0 && tick % 100 == 0) {
                    timeline.add(new TimelineEvent.SprintToggle(tick, uuid, ((tick / 100) % 2) == 1));
                }
                if (tick > 0 && tick % 120 == 0) {
                    timeline.add(new TimelineEvent.SneakToggle(tick, uuid, ((tick / 120) % 2) == 1));
                }
                if (tick > 0 && tick % 200 == 0) {
                    timeline.add(new TimelineEvent.BlockBreak(tick, uuid, "world", playerIndex * 3, 64, tick / 20,
                            "minecraft:stone"));
                }
                if (tick > 0 && tick % 260 == 0) {
                    timeline.add(new TimelineEvent.BlockPlace(tick, uuid, "world", playerIndex * 3 + 1, 64, tick / 20,
                            "minecraft:oak_planks", "minecraft:air"));
                }
                if (tick > 0 && tick % 400 == 0) {
                    timeline.add(new TimelineEvent.InventoryUpdate(tick, uuid,
                            "minecraft:diamond_sword", "minecraft:shield",
                            List.of("minecraft:diamond_helmet", "minecraft:diamond_chestplate", "minecraft:diamond_leggings", "minecraft:diamond_boots"),
                            List.of("minecraft:diamond_sword", "minecraft:golden_apple", "minecraft:cobblestone")));
                }
            }
            timeline.add(new TimelineEvent.PlayerQuit(preset.durationTicks(), uuid));
        }
        return timeline;
    }

    private record MeasuredRun(
            long archiveBytes,
            long payloadBytes,
            long finalizeNanos,
            long decodeNanos,
            long exportNanos,
            long seekBatchNanos
    ) {
    }
}