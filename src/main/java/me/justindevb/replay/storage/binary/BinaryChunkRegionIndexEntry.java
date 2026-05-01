package me.justindevb.replay.storage.binary;

import java.util.Objects;

/**
 * Frozen index row for finalized .brregion entries.
 */
public record BinaryChunkRegionIndexEntry(
        int localChunkX,
        int localChunkZ,
        int payloadOffset,
        int compressedLength,
        int uncompressedLength,
        BinaryChunkCompression compression
) {

    public BinaryChunkRegionIndexEntry {
        validateLocalCoordinate(localChunkX, "localChunkX");
        validateLocalCoordinate(localChunkZ, "localChunkZ");
        if (payloadOffset < 0) {
            throw new IllegalArgumentException("payloadOffset must not be negative");
        }
        if (compressedLength <= 0) {
            throw new IllegalArgumentException("compressedLength must be positive");
        }
        if (uncompressedLength <= 0) {
            throw new IllegalArgumentException("uncompressedLength must be positive");
        }
        compression = Objects.requireNonNull(compression, "compression");
    }

    private static void validateLocalCoordinate(int value, String fieldName) {
        if (value < BinaryReplayFormat.CHUNK_REGION_MIN_LOCAL_COORDINATE
                || value > BinaryReplayFormat.CHUNK_REGION_MAX_LOCAL_COORDINATE) {
            throw new IllegalArgumentException(fieldName + " must be between 0 and 31 inclusive");
        }
    }
}