package me.justindevb.replay.playback;

import me.justindevb.replay.chunk.ChunkCoordinate;
import me.justindevb.replay.chunk.ReplayChunkData;
import me.justindevb.replay.storage.binary.BinaryChunkPayloadCodec;
import me.justindevb.replay.storage.binary.BinaryPacketFriendlyChunkPayloadCodec;
import me.justindevb.replay.storage.binary.BinaryChunkRegionCodec;
import me.justindevb.replay.storage.binary.BinaryChunkRegionEntry;
import me.justindevb.replay.storage.binary.BinaryReplayFormat;
import net.jpountz.lz4.LZ4FrameInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves and decodes chunk baselines from replay chunk archive entries on demand.
 */
public final class ReplayChunkPlaybackCache {

    private final ReplayChunkData chunkData;
    private final BinaryChunkRegionCodec regionCodec;
    private final BinaryChunkPayloadCodec legacyPayloadCodec;
    private final BinaryPacketFriendlyChunkPayloadCodec packetFriendlyPayloadCodec;
    private final Logger logger;
    private final Map<String, BinaryChunkRegionCodec.DecodedBinaryChunkRegion> decodedRegions = new HashMap<>();
    private final Map<ChunkCoordinate, Optional<ReplayChunkSnapshot>> decodedChunks = new HashMap<>();

    public ReplayChunkPlaybackCache(ReplayChunkData chunkData) {
        this(
                chunkData,
                new BinaryChunkRegionCodec(),
                new BinaryChunkPayloadCodec(),
                new BinaryPacketFriendlyChunkPayloadCodec(),
                Logger.getLogger(ReplayChunkPlaybackCache.class.getName()));
    }

    ReplayChunkPlaybackCache(
            ReplayChunkData chunkData,
            BinaryChunkRegionCodec regionCodec,
            BinaryChunkPayloadCodec legacyPayloadCodec,
            BinaryPacketFriendlyChunkPayloadCodec packetFriendlyPayloadCodec
    ) {
        this(
                chunkData,
                regionCodec,
                legacyPayloadCodec,
                packetFriendlyPayloadCodec,
                Logger.getLogger(ReplayChunkPlaybackCache.class.getName()));
    }

    ReplayChunkPlaybackCache(
            ReplayChunkData chunkData,
            BinaryChunkRegionCodec regionCodec,
            BinaryChunkPayloadCodec legacyPayloadCodec,
            BinaryPacketFriendlyChunkPayloadCodec packetFriendlyPayloadCodec,
            Logger logger
    ) {
        this.chunkData = Objects.requireNonNull(chunkData, "chunkData");
        this.regionCodec = Objects.requireNonNull(regionCodec, "regionCodec");
        this.legacyPayloadCodec = Objects.requireNonNull(legacyPayloadCodec, "legacyPayloadCodec");
        this.packetFriendlyPayloadCodec = Objects.requireNonNull(packetFriendlyPayloadCodec, "packetFriendlyPayloadCodec");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public ReplayChunkData chunkData() {
        return chunkData;
    }

    public Optional<ReplayChunkSnapshot> loadChunk(ChunkCoordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        if (!chunkData.hasChunkData()) {
            return Optional.empty();
        }

        return decodedChunks.computeIfAbsent(coordinate, this::decodeChunk);
    }

    private Optional<ReplayChunkSnapshot> decodeChunk(ChunkCoordinate coordinate) {
        String entryName = BinaryReplayFormat.RESERVED_CHUNKS_PREFIX
            + me.justindevb.replay.storage.binary.BinaryChunkArchiveNaming.worldDirectory(coordinate.worldName())
            + "/r."
            + coordinate.regionKey().regionX()
            + "."
            + coordinate.regionKey().regionZ()
            + BinaryReplayFormat.CHUNK_REGION_FILE_EXTENSION;
        try {
            byte[] regionBytes = chunkData.regionEntries().get(entryName);
            if (regionBytes == null) {
                return Optional.empty();
            }

            BinaryChunkRegionCodec.DecodedBinaryChunkRegion decodedRegion = decodedRegions.computeIfAbsent(entryName, key -> {
                try {
                    return regionCodec.decode(regionBytes);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            for (BinaryChunkRegionEntry entry : decodedRegion.entries()) {
                if (entry.localChunkX() == coordinate.localChunkX() && entry.localChunkZ() == coordinate.localChunkZ()) {
                    byte[] payload = decompress(entry.compressedPayload());
                    return Optional.of(switch (chunkData.metadata().payloadFormat()) {
                        case BRCS -> new ReplayChunkSnapshot.LegacyBlockStateSnapshot(legacyPayloadCodec.decode(payload));
                        case BRCP -> new ReplayChunkSnapshot.PacketFriendlySnapshot(packetFriendlyPayloadCodec.decode(payload));
                    });
                }
            }
            return Optional.empty();
        } catch (IOException | RuntimeException ex) {
            logger.log(Level.WARNING,
                    "Failed to load replay chunk baseline for " + coordinate + " from " + entryName,
                    ex);
            return Optional.empty();
        }
    }

    private static byte[] decompress(byte[] compressedPayload) throws IOException {
        try (LZ4FrameInputStream lz4 = new LZ4FrameInputStream(new ByteArrayInputStream(compressedPayload))) {
            return lz4.readAllBytes();
        }
    }
}