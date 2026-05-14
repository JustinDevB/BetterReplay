package me.justindevb.replay.storage.binary;

import java.util.Objects;

/**
 * Additive manifest metadata for archives that include optional chunk baselines.
 */
public record BinaryReplayChunkMetadata(
        boolean hasChunkData,
        int chunkRegionEntryCount,
        int chunkEntryCount,
        String chunkCoordinateHash,
        String chunkPayloadFormat,
        int chunkPayloadVersion
) {

    public BinaryReplayChunkMetadata {
        chunkPayloadFormat = normalizeChunkPayloadFormat(hasChunkData, chunkPayloadFormat);
        chunkPayloadVersion = normalizeChunkPayloadVersion(hasChunkData, chunkPayloadFormat, chunkPayloadVersion);
        validate(hasChunkData, chunkRegionEntryCount, chunkEntryCount, chunkCoordinateHash, chunkPayloadFormat, chunkPayloadVersion);
    }

    public static BinaryReplayChunkMetadata none() {
        return new BinaryReplayChunkMetadata(false, 0, 0, null, null, 0);
    }

    public static BinaryReplayChunkMetadata present(int chunkRegionEntryCount, int chunkEntryCount, String chunkCoordinateHash) {
        return present(chunkRegionEntryCount, chunkEntryCount, chunkCoordinateHash, BinaryChunkPayloadFormat.legacyDefault());
    }

    public static BinaryReplayChunkMetadata present(
            int chunkRegionEntryCount,
            int chunkEntryCount,
            String chunkCoordinateHash,
            BinaryChunkPayloadFormat chunkPayloadFormat
    ) {
        Objects.requireNonNull(chunkPayloadFormat, "chunkPayloadFormat");
        return new BinaryReplayChunkMetadata(
                true,
                chunkRegionEntryCount,
                chunkEntryCount,
                chunkCoordinateHash,
                chunkPayloadFormat.manifestValue(),
                chunkPayloadFormat.currentVersion());
    }

    public BinaryChunkPayloadFormat payloadFormat() {
        return hasChunkData ? BinaryChunkPayloadFormat.fromManifestValue(chunkPayloadFormat) : null;
    }

    static void validate(
            boolean hasChunkData,
            int chunkRegionEntryCount,
            int chunkEntryCount,
            String chunkCoordinateHash,
            String chunkPayloadFormat,
            int chunkPayloadVersion
    ) {
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
            if (chunkPayloadFormat != null) {
                throw new IllegalArgumentException("chunkPayloadFormat must be absent when hasChunkData is false");
            }
            if (chunkPayloadVersion != 0) {
                throw new IllegalArgumentException("chunkPayloadVersion must be zero when hasChunkData is false");
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
        BinaryChunkPayloadFormat format = BinaryChunkPayloadFormat.fromManifestValue(chunkPayloadFormat);
        if (chunkPayloadVersion <= 0) {
            throw new IllegalArgumentException("chunkPayloadVersion must be positive when hasChunkData is true");
        }
        if (chunkPayloadVersion != format.currentVersion()) {
            throw new IllegalArgumentException("Unsupported chunk payload version for " + format.manifestValue() + ": " + chunkPayloadVersion);
        }
    }

    private static String normalizeChunkPayloadFormat(boolean hasChunkData, String chunkPayloadFormat) {
        if (!hasChunkData) {
            return chunkPayloadFormat;
        }
        return chunkPayloadFormat == null || chunkPayloadFormat.isBlank()
                ? BinaryChunkPayloadFormat.legacyDefault().manifestValue()
                : chunkPayloadFormat;
    }

    private static int normalizeChunkPayloadVersion(boolean hasChunkData, String chunkPayloadFormat, int chunkPayloadVersion) {
        if (!hasChunkData) {
            return chunkPayloadVersion;
        }
        if (chunkPayloadVersion != 0) {
            return chunkPayloadVersion;
        }
        return BinaryChunkPayloadFormat.fromManifestValue(chunkPayloadFormat).currentVersion();
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