package me.justindevb.replay.playback;

import me.justindevb.replay.chunk.ChunkCoordinate;
import me.justindevb.replay.chunk.ReplayChunkData;
import me.justindevb.replay.storage.binary.BinaryChunkCompression;
import me.justindevb.replay.storage.binary.BinaryChunkPayloadCodec;
import me.justindevb.replay.storage.binary.BinaryChunkPayloadFormat;
import me.justindevb.replay.storage.binary.BinaryPacketFriendlyChunkPayloadCodec;
import me.justindevb.replay.storage.binary.BinaryChunkRegionCodec;
import me.justindevb.replay.storage.binary.BinaryChunkRegionEntry;
import me.justindevb.replay.storage.binary.BinaryReplayChunkMetadata;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayChunkPlaybackCacheTest {

    private final BinaryChunkPayloadCodec payloadCodec = new BinaryChunkPayloadCodec();
    private final BinaryPacketFriendlyChunkPayloadCodec packetFriendlyPayloadCodec = new BinaryPacketFriendlyChunkPayloadCodec();
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
        Optional<ReplayChunkSnapshot> decoded = cache.loadChunk(new ChunkCoordinate("world", 0, 0));

        assertTrue(decoded.isPresent());
        ReplayChunkSnapshot.LegacyBlockStateSnapshot snapshot = (ReplayChunkSnapshot.LegacyBlockStateSnapshot) decoded.get();
        assertEquals(0, snapshot.payload().minY());
        assertEquals(1, snapshot.payload().height());
        assertEquals("minecraft:stone", snapshot.payload().palette().getFirst());
        assertEquals(16 * 16, snapshot.payload().stateIndexes().length);
    }

    @Test
    void loadChunkWithDiagnostics_reportsCacheHitAfterInitialDecode() throws Exception {
        byte[] payload = payloadCodec.encode(0, 1, List.of("minecraft:stone"), new short[16 * 16]);
        byte[] compressedPayload = compress(payload);
        byte[] regionBytes = regionCodec.encode(List.of(new BinaryChunkRegionEntry(0, 0, payload.length, BinaryChunkCompression.LZ4_FRAME, compressedPayload)));
        ReplayChunkData chunkData = new ReplayChunkData(
                BinaryReplayChunkMetadata.present(1, 1, "abcd"),
                Map.of("chunks/world/r.0.0.brregion", regionBytes));

        ReplayChunkPlaybackCache cache = new ReplayChunkPlaybackCache(chunkData);
        ReplayChunkPlaybackCache.ChunkLoadResult firstLoad = cache.loadChunkWithDiagnostics(new ChunkCoordinate("world", 0, 0));
        ReplayChunkPlaybackCache.ChunkLoadResult secondLoad = cache.loadChunkWithDiagnostics(new ChunkCoordinate("world", 0, 0));

        assertTrue(firstLoad.snapshot().isPresent());
        assertFalse(firstLoad.cacheHit());
        assertTrue(secondLoad.snapshot().isPresent());
        assertTrue(secondLoad.cacheHit());
    }

    @Test
    void loadChunk_returnsEmptyWhenRegionBytesAreCorrupt() {
        ReplayChunkData chunkData = new ReplayChunkData(
                BinaryReplayChunkMetadata.present(1, 1, "abcd"),
                Map.of("chunks/world/r.0.0.brregion", new byte[] { 1, 2, 3 }));

        TestLogHandler logHandler = new TestLogHandler();
        ReplayChunkPlaybackCache cache = new ReplayChunkPlaybackCache(
            chunkData,
            regionCodec,
            payloadCodec,
            packetFriendlyPayloadCodec,
            testLogger(logHandler));

        assertFalse(cache.loadChunk(new ChunkCoordinate("world", 0, 0)).isPresent());
        assertTrue(logHandler.contains(Level.WARNING, "Failed to load replay chunk baseline for ChunkCoordinate[worldName=world, chunkX=0, chunkZ=0] from chunks/world/r.0.0.brregion"));
    }

    @Test
    void loadChunk_decodesPacketFriendlySnapshotWhenArchiveUsesBrcp() throws Exception {
        BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload payload =
            new BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload(
                0,
                List.of(new BinaryPacketFriendlyChunkPayloadCodec.SectionPayload(
                    List.of("minecraft:air", "minecraft:stone"),
                        1,
                        new long[64],
                    List.of("minecraft:plains"),
                    0,
                    new long[0]
                )),
                List.of());
        byte[] encodedPayload = packetFriendlyPayloadCodec.encode(payload);
        byte[] compressedPayload = compress(encodedPayload);
        byte[] regionBytes = regionCodec.encode(List.of(new BinaryChunkRegionEntry(0, 0, encodedPayload.length, BinaryChunkCompression.LZ4_FRAME, compressedPayload)));
        ReplayChunkData chunkData = new ReplayChunkData(
            BinaryReplayChunkMetadata.present(1, 1, "abcd", BinaryChunkPayloadFormat.BRCP),
            Map.of("chunks/world/r.0.0.brregion", regionBytes));

        ReplayChunkPlaybackCache cache = new ReplayChunkPlaybackCache(chunkData);
        Optional<ReplayChunkSnapshot> decoded = cache.loadChunk(new ChunkCoordinate("world", 0, 0));

        assertTrue(decoded.isPresent());
        ReplayChunkSnapshot.PacketFriendlySnapshot snapshot = (ReplayChunkSnapshot.PacketFriendlySnapshot) decoded.get();
        assertEquals(0, snapshot.payload().minSectionY());
        assertEquals(1, snapshot.payload().sections().size());
        assertEquals(List.of("minecraft:air", "minecraft:stone"), snapshot.payload().sections().getFirst().blockPalette());
    }

    private static byte[] compress(byte[] payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(out)) {
            lz4.write(payload);
        }
        return out.toByteArray();
    }

    private static Logger testLogger(TestLogHandler handler) {
        Logger logger = Logger.getLogger("ReplayChunkPlaybackCacheTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        return logger;
    }

    private static final class TestLogHandler extends Handler {
        private final java.util.List<LogRecord> records = new java.util.ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        boolean contains(Level level, String messageFragment) {
            return records.stream().anyMatch(record -> record.getLevel().equals(level)
                    && record.getMessage() != null
                    && record.getMessage().contains(messageFragment));
        }
    }
}