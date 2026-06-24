package me.justindevb.replay.storage.binary;

import me.justindevb.replay.chunk.CapturedChunkBaseline;
import me.justindevb.replay.chunk.ChunkCoordinate;
import me.justindevb.replay.chunk.ChunkRecordingArtifacts;
import me.justindevb.replay.chunk.ReplayChunkData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryChunkTempArchiveFinalizerTest {

    private static final byte[] BRCS_PAYLOAD = new byte[] { 'B', 'R', 'C', 'S', 1 };
    private static final byte[] BRCP_PAYLOAD = new byte[] { 'B', 'R', 'C', 'P', 1 };

    private final BinaryChunkTempArchiveFinalizer finalizer = new BinaryChunkTempArchiveFinalizer();
    private final BinaryChunkRegionCodec regionCodec = new BinaryChunkRegionCodec();

    @TempDir
    Path tempDir;

    @Test
    void finalizeArtifacts_convertsTempRegionFilesIntoArchiveEntries() throws Exception {
        try (BinaryChunkTempRegionFileWriter writer = new BinaryChunkTempRegionFileWriter(tempDir)) {
            writer.append(new CapturedChunkBaseline(new ChunkCoordinate("world", 0, 0), BRCS_PAYLOAD));
            writer.append(new CapturedChunkBaseline(new ChunkCoordinate("world", 1, 1), BRCS_PAYLOAD));

            ReplayChunkData chunkData = finalizer.finalizeArtifacts(writer.snapshotArtifacts());

            assertTrue(chunkData.hasChunkData());
            assertEquals(1, chunkData.metadata().chunkRegionEntryCount());
            assertEquals(2, chunkData.metadata().chunkEntryCount());
            assertEquals(1, chunkData.regionEntries().size());

            Map.Entry<String, byte[]> entry = chunkData.regionEntries().entrySet().iterator().next();
            assertEquals("chunks/world/r.0.0.brregion", entry.getKey());
            assertEquals(2, regionCodec.decode(entry.getValue()).entries().size());
        }
    }

    @Test
    void finalizeArtifacts_keepsLastChunkRecordWhenTempRegionContainsDuplicates() throws Exception {
        Path root = tempDir.resolve("artifacts");
        try (BinaryChunkTempRegionFileWriter writer = new BinaryChunkTempRegionFileWriter(root)) {
            ChunkCoordinate coordinate = new ChunkCoordinate("world", 0, 0);
            writer.append(new CapturedChunkBaseline(coordinate, BRCS_PAYLOAD));
            writer.append(new CapturedChunkBaseline(coordinate, new byte[] { 'B', 'R', 'C', 'S', 1, 2, 3, 4 }));

            ReplayChunkData chunkData = finalizer.finalizeArtifacts(new ChunkRecordingArtifacts(root, 2, 1));
            BinaryChunkRegionEntry entry = regionCodec.decode(chunkData.regionEntries().values().iterator().next()).entries().getFirst();

            assertEquals(1, chunkData.metadata().chunkEntryCount());
            assertEquals(8, entry.uncompressedLength());
        }
    }

    @Test
    void finalizeArtifacts_preservesChunkPayloadFormatInMetadata() throws Exception {
        try (BinaryChunkTempRegionFileWriter writer = new BinaryChunkTempRegionFileWriter(tempDir)) {
            writer.append(new CapturedChunkBaseline(
                    new ChunkCoordinate("world", 0, 0),
                    BRCP_PAYLOAD,
                    BinaryChunkPayloadFormat.BRCP));

            ReplayChunkData chunkData = finalizer.finalizeArtifacts(writer.snapshotArtifacts());

            assertEquals(BinaryChunkPayloadFormat.BRCP, chunkData.metadata().payloadFormat());
            assertEquals(BinaryChunkPayloadFormat.BRCP.manifestValue(), chunkData.metadata().chunkPayloadFormat());
            assertEquals(1, chunkData.metadata().chunkPayloadVersion());
        }
    }

    @Test
    void finalizeArtifacts_infersPacketFriendlyFormatForRecoveredTempArtifacts() throws Exception {
        Path root = tempDir.resolve("recovered");
        try (BinaryChunkTempRegionFileWriter writer = new BinaryChunkTempRegionFileWriter(root)) {
            writer.append(new CapturedChunkBaseline(
                    new ChunkCoordinate("world", -5, -25),
                    BRCP_PAYLOAD,
                    BinaryChunkPayloadFormat.BRCP));

            ReplayChunkData chunkData = finalizer.finalizeArtifacts(new ChunkRecordingArtifacts(root, 0, 0));

            assertEquals(BinaryChunkPayloadFormat.BRCP, chunkData.metadata().payloadFormat());
            assertEquals(BinaryChunkPayloadFormat.BRCP.manifestValue(), chunkData.metadata().chunkPayloadFormat());
        }
    }
}
