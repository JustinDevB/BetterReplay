package me.justindevb.replay.storage.binary;

import me.justindevb.replay.recording.TimelineEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryReplayAppendLogRecoveryTest {

    private static final long RECORDING_STARTED_AT = 1_700_000_000_000L;

    @TempDir
    Path tempDir;

    @Test
    void stopsAtTruncatedTailAndKeepsValidPrefix() throws Exception {
        Path path = tempDir.resolve("truncated.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path, RECORDING_STARTED_AT)) {
            writer.append(new TimelineEvent.PlayerQuit(0, "uuid-1"));
            writer.append(new TimelineEvent.PlayerQuit(1, "uuid-2"));
            writer.flush();
        }

        byte[] bytes = Files.readAllBytes(path);
        Files.write(path, java.util.Arrays.copyOf(bytes, bytes.length - 2));

        BinaryReplayAppendLogRecovery recovery = reader.recover(path);

        assertEquals(BinaryReplayRecoveryStopReason.TRUNCATED_RECORD, recovery.stopReason());
        assertEquals(RECORDING_STARTED_AT, recovery.header().recordingStartedAtEpochMillis());
        assertEquals(List.of(new TimelineEvent.PlayerQuit(0, "uuid-1")), recovery.timeline());
        assertTrue(recovery.discardedTail());
    }

    @Test
    void stopsAtChecksumMismatchAndKeepsValidPrefix() throws Exception {
        Path path = tempDir.resolve("corrupt.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path, RECORDING_STARTED_AT)) {
            writer.append(new TimelineEvent.PlayerQuit(0, "uuid-1"));
            writer.append(new TimelineEvent.PlayerQuit(1, "uuid-2"));
            writer.flush();
        }

        byte[] bytes = Files.readAllBytes(path);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(path, bytes);

        BinaryReplayAppendLogRecovery recovery = reader.recover(path);

        assertEquals(BinaryReplayRecoveryStopReason.CHECKSUM_MISMATCH, recovery.stopReason());
        assertEquals(RECORDING_STARTED_AT, recovery.header().recordingStartedAtEpochMillis());
        assertEquals(List.of(new TimelineEvent.PlayerQuit(0, "uuid-1")), recovery.timeline());
        assertFalse(recovery.records().isEmpty());
    }

    @Test
    void failsRecoveryWhenHeaderIsTruncated() throws Exception {
        Path path = tempDir.resolve("header-truncated.appendlog");
        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path, RECORDING_STARTED_AT)) {
            writer.flush();
        }

        byte[] bytes = Files.readAllBytes(path);
        Files.write(path, java.util.Arrays.copyOf(bytes, BinaryReplayFormat.APPEND_LOG_HEADER_SIZE - 1));

        BinaryReplayAppendLogRecovery recovery = new BinaryReplayAppendLogReader().recover(path);

        assertEquals(BinaryReplayRecoveryStopReason.TRUNCATED_HEADER, recovery.stopReason());
        assertEquals(List.of(), recovery.timeline());
    }
}