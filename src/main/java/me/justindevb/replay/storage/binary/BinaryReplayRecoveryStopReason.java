package me.justindevb.replay.storage.binary;

/**
 * Explains why append-log recovery stopped scanning a temp file.
 */
public enum BinaryReplayRecoveryStopReason {
    CLEAN_EOF,
    TRUNCATED_HEADER,
    TRUNCATED_RECORD_LENGTH,
    TRUNCATED_RECORD,
    CHECKSUM_MISMATCH,
    MALFORMED_HEADER,
    MALFORMED_RECORD
}