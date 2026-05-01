package me.justindevb.replay.storage.binary;

import java.util.Objects;

/**
 * Append-friendly per-chunk temp record written before region finalization.
 */
public record BinaryChunkTempRegionAppendRecord(
        int localChunkX,
        int localChunkZ,
        int uncompressedLength,
        BinaryChunkCompression compression,
        byte[] compressedPayload
) {

    public BinaryChunkTempRegionAppendRecord {
        if (localChunkX < BinaryReplayFormat.CHUNK_REGION_MIN_LOCAL_COORDINATE
                || localChunkX > BinaryReplayFormat.CHUNK_REGION_MAX_LOCAL_COORDINATE) {
            throw new IllegalArgumentException("localChunkX must be between 0 and 31 inclusive");
        }
        if (localChunkZ < BinaryReplayFormat.CHUNK_REGION_MIN_LOCAL_COORDINATE
                || localChunkZ > BinaryReplayFormat.CHUNK_REGION_MAX_LOCAL_COORDINATE) {
            throw new IllegalArgumentException("localChunkZ must be between 0 and 31 inclusive");
        }
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
}