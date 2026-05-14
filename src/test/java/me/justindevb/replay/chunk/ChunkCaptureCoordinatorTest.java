package me.justindevb.replay.chunk;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkCaptureCoordinatorTest {

    @Test
    void captureTrackedChunks_deduplicatesPreviouslyCapturedChunks() throws Exception {
        RecordingWriterStub writer = new RecordingWriterStub();
        ChunkCaptureCoordinator coordinator = new ChunkCaptureCoordinator(
                new ChunkCaptureConfig(true, 1, 20, 100),
                new RadiusChunkInterestTracker(),
                coordinate -> new CapturedChunkBaseline(coordinate, new byte[] { 1, 2, 3 }),
                writer);

        ChunkCaptureCoordinator.CaptureResult firstPass = coordinator.captureTrackedChunks(List.of(new ChunkCoordinate("world", 0, 0)));
        ChunkCaptureCoordinator.CaptureResult secondPass = coordinator.captureTrackedChunks(List.of(new ChunkCoordinate("world", 0, 0)));

        assertEquals(9, firstPass.capturedThisPass());
        assertEquals(0, secondPass.capturedThisPass());
        assertEquals(9, writer.baselines.size());
    }

    @Test
    void captureTrackedChunks_respectsMaxUniqueChunkCap() throws Exception {
        RecordingWriterStub writer = new RecordingWriterStub();
        ChunkCaptureCoordinator coordinator = new ChunkCaptureCoordinator(
                new ChunkCaptureConfig(true, 1, 20, 4),
                new RadiusChunkInterestTracker(),
                coordinate -> new CapturedChunkBaseline(coordinate, new byte[] { 9 }),
                writer);

        ChunkCaptureCoordinator.CaptureResult result = coordinator.captureTrackedChunks(List.of(new ChunkCoordinate("world", 0, 0)));

        assertEquals(4, result.capturedThisPass());
        assertEquals(4, result.totalCaptured());
        assertTrue(result.truncated());
        assertEquals(4, writer.baselines.size());
    }

    private static final class RecordingWriterStub implements ChunkTempRegionWriter {
        private final List<CapturedChunkBaseline> baselines = new ArrayList<>();

        @Override
        public void append(CapturedChunkBaseline baseline) {
            baselines.add(baseline);
        }

        @Override
        public ChunkRecordingArtifacts snapshotArtifacts() {
            return new ChunkRecordingArtifacts(Path.of("chunks"), baselines.size(), 1);
        }

        @Override
        public void close() throws IOException {
        }
    }
}