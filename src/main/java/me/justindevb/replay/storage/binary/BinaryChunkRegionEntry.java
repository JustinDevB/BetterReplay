package me.justindevb.replay.storage.binary;

import java.util.Arrays;
import java.util.Objects;

/**
 * Logical chunk payload written into a finalized region entry.
 */
public record BinaryChunkRegionEntry(
        int localChunkX,
        int localChunkZ,
        int uncompressedLength,
        BinaryChunkCompression compression,
        byte[] compressedPayload
) {

    public BinaryChunkRegionEntry {
        validateLocalCoordinate(localChunkX, "localChunkX");
        validateLocalCoordinate(localChunkZ, "localChunkZ");
        if (uncompressedLength <= 0) {
            throw new IllegalArgumentException("uncompressedLength must be positive");
        }
        compression = Objects.requireNonNull(compression, "compression");
        compressedPayload = Objects.requireNonNull(compressedPayload, "compressedPayload").clone();
        if (compressedPayload.length == 0) {
            throw new IllegalArgumentException("compressedPayload must not be empty");
        }
    }

    @Override
    public byte[] compressedPayload() {
        return compressedPayload.clone();
    }

    boolean sameChunk(BinaryChunkRegionEntry other) {
        return other != null && localChunkX == other.localChunkX && localChunkZ == other.localChunkZ;
    }

    private static void validateLocalCoordinate(int value, String fieldName) {
        if (value < BinaryReplayFormat.CHUNK_REGION_MIN_LOCAL_COORDINATE
                || value > BinaryReplayFormat.CHUNK_REGION_MAX_LOCAL_COORDINATE) {
            throw new IllegalArgumentException(fieldName + " must be between 0 and 31 inclusive");
        }
    }
}