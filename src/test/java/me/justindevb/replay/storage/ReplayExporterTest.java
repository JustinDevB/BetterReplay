package me.justindevb.replay.storage;

import me.justindevb.replay.api.ReplayExportQuery;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.binary.BinaryReplayStorageCodec;
import me.justindevb.replay.util.VersionUtil;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayExporterTest {

    private final BinaryReplayStorageCodec codec = new BinaryReplayStorageCodec();
    private final ReplayExporter exporter = new ReplayExporter();

    @Test
    void exportsFullReplayWhenFiltersAreOmitted() throws Exception {
        List<TimelineEvent> exported = exportTimeline(ReplayExportQuery.all());

        assertEquals(sampleTimeline(), exported);
    }

    @Test
    void exportsSinglePlayerByRecordedName() throws Exception {
        List<TimelineEvent> exported = exportTimeline(new ReplayExportQuery("Steve", null, null));

        assertEquals(List.of(
                new TimelineEvent.PlayerMove(0, "uuid-1", "Steve", "world", 1, 64, 1, 0, 0, "STANDING"),
                new TimelineEvent.BlockBreak(5, "uuid-1", "world", 1, 64, 1, "minecraft:stone"),
                new TimelineEvent.PlayerQuit(20, "uuid-1")
        ), exported);
    }

    @Test
    void exportsBoundedTickRange() throws Exception {
        List<TimelineEvent> exported = exportTimeline(new ReplayExportQuery(null, 10, 20));

        assertEquals(List.of(
                new TimelineEvent.PlayerMove(10, "uuid-2", "Alex", "world", 2, 64, 2, 0, 0, "STANDING"),
                new TimelineEvent.SprintToggle(15, "uuid-2", true),
                new TimelineEvent.PlayerQuit(20, "uuid-1")
        ), exported);
    }

    @Test
    void exportsFullReplayWhenPlayerIsAll() throws Exception {
        List<TimelineEvent> exported = exportTimeline(new ReplayExportQuery("all", null, null));

        assertEquals(sampleTimeline(), exported);
    }

    @Test
    void exportsWithBothPlayerAndTickRange() throws Exception {
        List<TimelineEvent> exported = exportTimeline(new ReplayExportQuery("Alex", 10, 15));

        assertEquals(List.of(
                new TimelineEvent.PlayerMove(10, "uuid-2", "Alex", "world", 2, 64, 2, 0, 0, "STANDING"),
                new TimelineEvent.SprintToggle(15, "uuid-2", true)
        ), exported);
    }

    private List<TimelineEvent> exportTimeline(ReplayExportQuery query) throws Exception {
        byte[] archive = codec.finalizeReplay("sample", sampleTimeline(), VersionUtil.MIN_RECORDING_VERSION);
        List<TimelineEvent> timeline = codec.decodeTimeline(archive, VersionUtil.MIN_RECORDING_VERSION);

        File exported = exporter.exportReplay("sample", timeline, query, VersionUtil.MIN_RECORDING_VERSION);

        return codec.decodeTimeline(Files.readAllBytes(exported.toPath()), VersionUtil.MIN_RECORDING_VERSION);
    }

    private static List<TimelineEvent> sampleTimeline() {
        return List.of(
                new TimelineEvent.PlayerMove(0, "uuid-1", "Steve", "world", 1, 64, 1, 0, 0, "STANDING"),
                new TimelineEvent.BlockBreak(5, "uuid-1", "world", 1, 64, 1, "minecraft:stone"),
                new TimelineEvent.PlayerMove(10, "uuid-2", "Alex", "world", 2, 64, 2, 0, 0, "STANDING"),
                new TimelineEvent.SprintToggle(15, "uuid-2", true),
                new TimelineEvent.PlayerQuit(20, "uuid-1"),
                new TimelineEvent.PlayerQuit(25, "uuid-2")
        );
    }
}
