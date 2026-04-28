package me.justindevb.replay.storage.binary;

/**
 * Metadata stored in the fixed append-log file header before any framed records.
 */
public record BinaryReplayAppendLogHeader(long recordingStartedAtEpochMillis) {

    public BinaryReplayAppendLogHeader {
        if (recordingStartedAtEpochMillis < 0) {
            throw new IllegalArgumentException("recordingStartedAtEpochMillis must be non-negative");
        }
    }

    public static BinaryReplayAppendLogHeader empty() {
        return new BinaryReplayAppendLogHeader(0);
    }
}