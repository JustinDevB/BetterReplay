package me.justindevb.replay.storage.binary;

import java.util.Objects;

/**
 * Additive manifest metadata for archives that include optional chunk baselines.
 */
public record BinaryReplayChunkMetadata(
        boolean hasChunkData,
        int chunkRegionEntryCount,
        int chunkEntryCount,
        String chunkCoordinateHash
) {

    public BinaryReplayChunkMetadata {
        validate(hasChunkData, chunkRegionEntryCount, chunkEntryCount, chunkCoordinateHash);
    }

    public static BinaryReplayChunkMetadata none() {
        return new BinaryReplayChunkMetadata(false, 0, 0, null);
    }

    public static BinaryReplayChunkMetadata present(int chunkRegionEntryCount, int chunkEntryCount, String chunkCoordinateHash) {
        return new BinaryReplayChunkMetadata(true, chunkRegionEntryCount, chunkEntryCount, chunkCoordinateHash);
    }

    static void validate(boolean hasChunkData, int chunkRegionEntryCount, int chunkEntryCount, String chunkCoordinateHash) {
        if (chunkRegionEntryCount < 0) {
            throw new IllegalArgumentException("chunkRegionEntryCount must not be negative");
        }
        if (chunkEntryCount < 0) {
            throw new IllegalArgumentException("chunkEntryCount must not be negative");
        }
        if (!hasChunkData) {
            if (chunkRegionEntryCount != 0) {
                throw new IllegalArgumentException("chunkRegionEntryCount must be zero when hasChunkData is false");
            }
            if (chunkEntryCount != 0) {
                throw new IllegalArgumentException("chunkEntryCount must be zero when hasChunkData is false");
            }
            if (chunkCoordinateHash != null) {
                throw new IllegalArgumentException("chunkCoordinateHash must be absent when hasChunkData is false");
            }
            return;
        }

        if (chunkRegionEntryCount == 0) {
            throw new IllegalArgumentException("chunkRegionEntryCount must be positive when hasChunkData is true");
        }
        if (chunkEntryCount == 0) {
            throw new IllegalArgumentException("chunkEntryCount must be positive when hasChunkData is true");
        }
        if (chunkRegionEntryCount > chunkEntryCount) {
            throw new IllegalArgumentException("chunkRegionEntryCount must not exceed chunkEntryCount");
        }
        if (chunkCoordinateHash != null) {
            requireLowerHex(chunkCoordinateHash, "chunkCoordinateHash");
        }
    }

    private static void requireLowerHex(String value, String fieldName) {
        if (Objects.requireNonNull(value, fieldName).isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (!value.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException(fieldName + " must be lowercase hexadecimal");
        }
    }
}