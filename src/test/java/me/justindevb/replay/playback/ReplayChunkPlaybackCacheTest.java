package me.justindevb.replay.playback;

import me.justindevb.replay.chunk.ChunkCoordinate;
import me.justindevb.replay.chunk.ReplayChunkData;
import me.justindevb.replay.storage.binary.BinaryChunkCompression;
import me.justindevb.replay.storage.binary.BinaryChunkPayloadCodec;
import me.justindevb.replay.storage.binary.BinaryChunkRegionCodec;
import me.justindevb.replay.storage.binary.BinaryChunkRegionEntry;
import me.justindevb.replay.storage.binary.BinaryReplayChunkMetadata;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayChunkPlaybackCacheTest {

    private final BinaryChunkPayloadCodec payloadCodec = new BinaryChunkPayloadCodec();
    private final BinaryChunkRegionCodec regionCodec = new BinaryChunkRegionCodec();

    @Test
    void loadChunk_decodesStoredChunkPayloadOnDemand() throws Exception {
        byte[] payload = payloadCodec.encode(0, 1, List.of("minecraft:stone"), new short[16 * 16]);
        byte[] compressedPayload = compress(payload);
        byte[] regionBytes = regionCodec.encode(List.of(new BinaryChunkRegionEntry(0, 0, payload.length, BinaryChunkCompression.LZ4_FRAME, compressedPayload)));
        ReplayChunkData chunkData = new ReplayChunkData(
                BinaryReplayChunkMetadata.present(1, 1, "abcd"),
                Map.of("chunks/world/r.0.0.brregion", regionBytes));

        ReplayChunkPlaybackCache cache = new ReplayChunkPlaybackCache(chunkData);
        Optional<BinaryChunkPayloadCodec.DecodedChunkPayload> decoded = cache.loadChunk(new ChunkCoordinate("world", 0, 0));

        assertTrue(decoded.isPresent());
        assertEquals(0, decoded.get().minY());
        assertEquals(1, decoded.get().height());
        assertEquals("minecraft:stone", decoded.get().palette().getFirst());
        assertEquals(16 * 16, decoded.get().stateIndexes().length);
    }

    @Test
    void loadChunk_returnsEmptyWhenRegionBytesAreCorrupt() {
        ReplayChunkData chunkData = new ReplayChunkData(
                BinaryReplayChunkMetadata.present(1, 1, "abcd"),
                Map.of("chunks/world/r.0.0.brregion", new byte[] { 1, 2, 3 }));

        ReplayChunkPlaybackCache cache = new ReplayChunkPlaybackCache(chunkData);

        assertFalse(cache.loadChunk(new ChunkCoordinate("world", 0, 0)).isPresent());
    }

    private static byte[] compress(byte[] payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(out)) {
            lz4.write(payload);
        }
        return out.toByteArray();
    }
}