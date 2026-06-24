package me.justindevb.replay.storage;

import me.justindevb.replay.api.ReplayExportQuery;
import me.justindevb.replay.chunk.CapturedChunkBaseline;
import me.justindevb.replay.chunk.ChunkCoordinate;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.binary.BinaryChunkRegionCodec;
import me.justindevb.replay.storage.binary.BinaryChunkTempRegionFileWriter;
import me.justindevb.replay.storage.binary.BinaryReplayStorageCodec;
import me.justindevb.replay.util.VersionUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayExporterTest {

    private final BinaryReplayStorageCodec codec = new BinaryReplayStorageCodec();
    private final ReplayExporter exporter = new ReplayExporter();

    @TempDir
    Path tempDir;

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

    @Test
    void exportsAllPlayersWithAllChunkData() throws Exception {
        ReplayPlaybackData exported = exportReplayDataWithChunks(ReplayExportQuery.all());

        assertTrue(exported.chunkData().hasChunkData());
        assertEquals(1, exported.chunkData().metadata().chunkRegionEntryCount());
        assertEquals(2, exported.chunkData().metadata().chunkEntryCount());
    }

    @Test
    void exportsSinglePlayerWithOnlyMovedThroughChunks() throws Exception {
        ReplayPlaybackData exported = exportReplayDataWithChunks(new ReplayExportQuery("Steve", null, null));

        assertTrue(exported.chunkData().hasChunkData());
        assertEquals(1, exported.chunkData().metadata().chunkRegionEntryCount());
        assertEquals(1, exported.chunkData().metadata().chunkEntryCount());
        BinaryChunkRegionCodec.DecodedBinaryChunkRegion region = new BinaryChunkRegionCodec()
                .decode(exported.chunkData().regionEntries().get("chunks/world/r.0.0.brregion"));
        assertEquals(0, region.indexEntries().getFirst().localChunkX());
        assertEquals(0, region.indexEntries().getFirst().localChunkZ());
    }

    private List<TimelineEvent> exportTimeline(ReplayExportQuery query) throws Exception {
        byte[] archive = codec.finalizeReplay("sample", sampleTimeline(), VersionUtil.MIN_RECORDING_VERSION);
        List<TimelineEvent> timeline = codec.decodeTimeline(archive, VersionUtil.MIN_RECORDING_VERSION);

        File exported = exporter.exportReplay("sample", timeline, query, VersionUtil.MIN_RECORDING_VERSION);

        return codec.decodeTimeline(Files.readAllBytes(exported.toPath()), VersionUtil.MIN_RECORDING_VERSION);
    }

    private ReplayPlaybackData exportReplayDataWithChunks(ReplayExportQuery query) throws Exception {
        try (BinaryChunkTempRegionFileWriter writer = new BinaryChunkTempRegionFileWriter(tempDir.resolve("chunk-artifacts"))) {
            writer.append(new CapturedChunkBaseline(new ChunkCoordinate("world", 0, 0), new byte[] { 'B', 'R', 'C', 'S', 1 }));
            writer.append(new CapturedChunkBaseline(new ChunkCoordinate("world", 2, 0), new byte[] { 'B', 'R', 'C', 'S', 2 }));
            byte[] archive = codec.finalizeReplay("sample", new ReplaySaveRequest(chunkTimeline(), 1_700_000_000_000L, writer.snapshotArtifacts()),
                    VersionUtil.MIN_RECORDING_VERSION);
            ReplayPlaybackData replayData = codec.decodeReplayData(archive, VersionUtil.MIN_RECORDING_VERSION);

            File exported = exporter.exportReplay("sample", replayData, query, VersionUtil.MIN_RECORDING_VERSION);

            return codec.decodeReplayData(Files.readAllBytes(exported.toPath()), VersionUtil.MIN_RECORDING_VERSION);
        }
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

    private static List<TimelineEvent> chunkTimeline() {
        return List.of(
                new TimelineEvent.PlayerMove(0, "uuid-1", "Steve", "world", 1, 64, 1, 0, 0, "STANDING"),
                new TimelineEvent.PlayerMove(10, "uuid-2", "Alex", "world", 33, 64, 1, 0, 0, "STANDING")
        );
    }
}
