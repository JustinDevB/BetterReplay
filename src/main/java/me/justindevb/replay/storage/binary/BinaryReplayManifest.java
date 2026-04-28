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
        String payloadChecksumAlgorithm
) {

    public BinaryReplayManifest {
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
                BinaryReplayFormat.PAYLOAD_CHECKSUM_ALGORITHM
        );
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