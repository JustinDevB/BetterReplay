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

    private final BinaryChunkTempArchiveFinalizer finalizer = new BinaryChunkTempArchiveFinalizer();
    private final BinaryChunkRegionCodec regionCodec = new BinaryChunkRegionCodec();

    @TempDir
    Path tempDir;

    @Test
    void finalizeArtifacts_convertsTempRegionFilesIntoArchiveEntries() throws Exception {
        BinaryChunkTempRegionFileWriter writer = new BinaryChunkTempRegionFileWriter(tempDir);
        writer.append(new CapturedChunkBaseline(new ChunkCoordinate("world", 0, 0), new byte[] { 1, 2, 3 }));
        writer.append(new CapturedChunkBaseline(new ChunkCoordinate("world", 1, 1), new byte[] { 4, 5 }));

        ReplayChunkData chunkData = finalizer.finalizeArtifacts(writer.snapshotArtifacts());

        assertTrue(chunkData.hasChunkData());
        assertEquals(1, chunkData.metadata().chunkRegionEntryCount());
        assertEquals(2, chunkData.metadata().chunkEntryCount());
        assertEquals(1, chunkData.regionEntries().size());

        Map.Entry<String, byte[]> entry = chunkData.regionEntries().entrySet().iterator().next();
        assertEquals("chunks/world/r.0.0.brregion", entry.getKey());
        assertEquals(2, regionCodec.decode(entry.getValue()).entries().size());
    }

    @Test
    void finalizeArtifacts_keepsLastChunkRecordWhenTempRegionContainsDuplicates() throws Exception {
        Path root = tempDir.resolve("artifacts");
        BinaryChunkTempRegionFileWriter writer = new BinaryChunkTempRegionFileWriter(root);
        ChunkCoordinate coordinate = new ChunkCoordinate("world", 0, 0);
        writer.append(new CapturedChunkBaseline(coordinate, new byte[] { 1 }));
        writer.append(new CapturedChunkBaseline(coordinate, new byte[] { 1, 2, 3, 4 }));

        ReplayChunkData chunkData = finalizer.finalizeArtifacts(new ChunkRecordingArtifacts(root, 2, 1));
        BinaryChunkRegionEntry entry = regionCodec.decode(chunkData.regionEntries().values().iterator().next()).entries().getFirst();

        assertEquals(1, chunkData.metadata().chunkEntryCount());
        assertEquals(4, entry.uncompressedLength());
    }

    @Test
    void finalizeArtifacts_preservesChunkPayloadFormatInMetadata() throws Exception {
        BinaryChunkTempRegionFileWriter writer = new BinaryChunkTempRegionFileWriter(tempDir);
        writer.append(new CapturedChunkBaseline(
                new ChunkCoordinate("world", 0, 0),
                new byte[] { 1, 2, 3 },
                BinaryChunkPayloadFormat.BRCP));

        ReplayChunkData chunkData = finalizer.finalizeArtifacts(writer.snapshotArtifacts());

        assertEquals(BinaryChunkPayloadFormat.BRCP, chunkData.metadata().payloadFormat());
        assertEquals(BinaryChunkPayloadFormat.BRCP.manifestValue(), chunkData.metadata().chunkPayloadFormat());
        assertEquals(1, chunkData.metadata().chunkPayloadVersion());
    }
}