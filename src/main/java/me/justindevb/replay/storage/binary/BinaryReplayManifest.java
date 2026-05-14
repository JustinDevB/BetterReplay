package me.justindevb.replay.storage.binary;

import java.util.Objects;

/**
 * Manifest schema for the root-level manifest.json entry inside a finalized .br archive.
 */
public record BinaryReplayManifest(
        int formatVersion,
        String recordedWithVersion,
        String minimumViewerVersion,
        long recordingStartedAtEpochMillis,
        String payloadChecksum,
        String payloadChecksumAlgorithm,
        boolean hasChunkData,
        int chunkRegionEntryCount,
        int chunkEntryCount,
        String chunkCoordinateHash,
        String chunkPayloadFormat,
        int chunkPayloadVersion
) {

    public BinaryReplayManifest {
        BinaryReplayChunkMetadata normalizedChunkMetadata = new BinaryReplayChunkMetadata(
                hasChunkData,
                chunkRegionEntryCount,
                chunkEntryCount,
                chunkCoordinateHash,
                chunkPayloadFormat,
                chunkPayloadVersion);
        hasChunkData = normalizedChunkMetadata.hasChunkData();
        chunkRegionEntryCount = normalizedChunkMetadata.chunkRegionEntryCount();
        chunkEntryCount = normalizedChunkMetadata.chunkEntryCount();
        chunkCoordinateHash = normalizedChunkMetadata.chunkCoordinateHash();
        chunkPayloadFormat = normalizedChunkMetadata.chunkPayloadFormat();
        chunkPayloadVersion = normalizedChunkMetadata.chunkPayloadVersion();

        if (formatVersion < 1) {
            throw new IllegalArgumentException("formatVersion must be positive");
        }
        requireNonBlank(recordedWithVersion, "recordedWithVersion");
        requireNonBlank(minimumViewerVersion, "minimumViewerVersion");
        if (recordingStartedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("recordingStartedAtEpochMillis must be positive");
        }
        requireLowerHex(payloadChecksum, "payloadChecksum");
        requireNonBlank(payloadChecksumAlgorithm, "payloadChecksumAlgorithm");
        BinaryReplayChunkMetadata.validate(
            hasChunkData,
            chunkRegionEntryCount,
            chunkEntryCount,
            chunkCoordinateHash,
            chunkPayloadFormat,
            chunkPayloadVersion);
    }

    public static BinaryReplayManifest createV1(
            String recordedWithVersion,
            String minimumViewerVersion,
            long recordingStartedAtEpochMillis,
            String payloadChecksum
    ) {
        return new BinaryReplayManifest(
                BinaryReplayFormat.FORMAT_VERSION,
                recordedWithVersion,
                minimumViewerVersion,
                recordingStartedAtEpochMillis,
                payloadChecksum,
                BinaryReplayFormat.PAYLOAD_CHECKSUM_ALGORITHM,
                false,
                0,
                0,
                null,
                null,
                0
        );
    }

    public static BinaryReplayManifest createV1(
            String recordedWithVersion,
            String minimumViewerVersion,
            long recordingStartedAtEpochMillis,
            String payloadChecksum,
            BinaryReplayChunkMetadata chunkMetadata
    ) {
        Objects.requireNonNull(chunkMetadata, "chunkMetadata");
        return new BinaryReplayManifest(
                BinaryReplayFormat.FORMAT_VERSION,
                recordedWithVersion,
                minimumViewerVersion,
                recordingStartedAtEpochMillis,
                payloadChecksum,
                BinaryReplayFormat.PAYLOAD_CHECKSUM_ALGORITHM,
                chunkMetadata.hasChunkData(),
                chunkMetadata.chunkRegionEntryCount(),
                chunkMetadata.chunkEntryCount(),
                chunkMetadata.chunkCoordinateHash(),
                chunkMetadata.chunkPayloadFormat(),
                chunkMetadata.chunkPayloadVersion()
        );
    }

    public BinaryReplayChunkMetadata chunkMetadata() {
        return new BinaryReplayChunkMetadata(
                hasChunkData,
                chunkRegionEntryCount,
                chunkEntryCount,
                chunkCoordinateHash,
                chunkPayloadFormat,
                chunkPayloadVersion);
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (Objects.requireNonNull(value, fieldName).isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static void requireLowerHex(String value, String fieldName) {
        requireNonBlank(value, fieldName);
        if (!value.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException(fieldName + " must be lowercase hexadecimal");
        }
    }
}