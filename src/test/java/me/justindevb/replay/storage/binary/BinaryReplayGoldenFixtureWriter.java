package me.justindevb.replay.storage.binary;

import me.justindevb.replay.recording.TimelineEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One-off utility for generating checked-in binary replay fixtures under src/test/resources.
 */
public final class BinaryReplayGoldenFixtureWriter {

    private static final long MINIMAL_RECORDING_STARTED_AT = 1_700_000_000_000L;
    private static final long SEEK_RECORDING_STARTED_AT = 1_700_000_050_000L;

    private BinaryReplayGoldenFixtureWriter() {
    }

    public static void main(String[] args) throws IOException {
        Path outputDir = Path.of("src", "test", "resources", "goldens");
        Files.createDirectories(outputDir);

        BinaryReplayStorageCodec codec = new BinaryReplayStorageCodec();
        Files.write(outputDir.resolve("minimal-v1.br"), codec.finalizeReplay("minimal", minimalTimeline(), "1.4.0", MINIMAL_RECORDING_STARTED_AT));
        Files.write(outputDir.resolve("seek-v1.br"), codec.finalizeReplay("seek", seekTimeline(), "1.4.0", SEEK_RECORDING_STARTED_AT));
    }

    private static List<TimelineEvent> minimalTimeline() {
        return List.of(
                new TimelineEvent.PlayerMove(0, "uuid-1", "Steve", "world", 1, 64, 3, 0, 0, "STANDING"),
                new TimelineEvent.BlockBreak(5, "uuid-1", "world", 10, 64, 20, "minecraft:stone"),
                new TimelineEvent.PlayerQuit(10, "uuid-1")
        );
    }

    private static List<TimelineEvent> seekTimeline() {
        List<TimelineEvent> timeline = new ArrayList<>();
        for (int tick = 0; tick <= 150; tick += 25) {
            timeline.add(new TimelineEvent.PlayerQuit(tick, "uuid-" + tick));
        }
        return timeline;
    }
}