package me.justindevb.replay.storage.binary;

import me.justindevb.replay.recording.TimelineEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

class BinaryReplayAppendLogWriterTest {

    private static final long RECORDING_STARTED_AT = 1_700_000_000_000L;

    @TempDir
    Path tempDir;

    @Test
    void appendsMultipleEventTypesAndReadsThemBack() throws Exception {
        Path path = tempDir.resolve("multi.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path, RECORDING_STARTED_AT)) {
            writer.append(new TimelineEvent.PlayerQuit(0, "uuid-1"));
            writer.append(new TimelineEvent.SprintToggle(5, "uuid-1", true));
            writer.append(new TimelineEvent.BlockBreakStage(8, null, "world", 1, 2, 3, 4));
            writer.flush();
        }

        List<TimelineEvent> timeline = reader.readTimeline(path);

        assertEquals(3, timeline.size());
        assertEquals(new TimelineEvent.PlayerQuit(0, "uuid-1"), timeline.get(0));
        assertEquals(new TimelineEvent.SprintToggle(5, "uuid-1", true), timeline.get(1));
        assertEquals(new TimelineEvent.BlockBreakStage(8, null, "world", 1, 2, 3, 4), timeline.get(2));
    }

    @Test
    void reusesStringDefinitionsAfterFirstUse() throws Exception {
        Path path = tempDir.resolve("reuse.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path, RECORDING_STARTED_AT)) {
            writer.append(new TimelineEvent.PlayerQuit(0, "shared-uuid"));
            writer.append(new TimelineEvent.PlayerQuit(1, "shared-uuid"));
            writer.flush();
        }

        List<BinaryReplayAppendLogReader.DecodedRecord> records = reader.readRecords(path);

        assertEquals(3, records.size());
        assertEquals(BinaryRecordType.DEFINE_STRING, records.get(0).type());
        assertEquals(BinaryRecordType.PLAYER_QUIT, records.get(1).type());
        assertEquals(BinaryRecordType.PLAYER_QUIT, records.get(2).type());
    }

    @Test
    void storesCrc32cForEachRecordContent() throws Exception {
        Path path = tempDir.resolve("crc.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path, RECORDING_STARTED_AT)) {
            writer.append(new TimelineEvent.PlayerQuit(0, "uuid-1"));
            writer.flush();
        }

        List<BinaryReplayAppendLogReader.DecodedRecord> records = reader.readRecords(path);
        assertFalse(records.isEmpty());

        for (BinaryReplayAppendLogReader.DecodedRecord record : records) {
            CRC32C crc32c = new CRC32C();
            crc32c.update(record.checksummedBytes(), 0, record.checksummedBytes().length);
            assertEquals((int) crc32c.getValue(), record.storedChecksum());
        }
    }

    @Test
    void keepsSimultaneousWritersIsolated() throws Exception {
        Path firstPath = tempDir.resolve("first.appendlog");
        Path secondPath = tempDir.resolve("second.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

           try (BinaryReplayAppendLogWriter first = new BinaryReplayAppendLogWriter(firstPath, RECORDING_STARTED_AT);
               BinaryReplayAppendLogWriter second = new BinaryReplayAppendLogWriter(secondPath, RECORDING_STARTED_AT + 1)) {
            first.append(new TimelineEvent.PlayerQuit(0, "first"));
            second.append(new TimelineEvent.PlayerQuit(0, "second"));
            first.flush();
            second.flush();
        }

        List<TimelineEvent> firstTimeline = reader.readTimeline(firstPath);
        List<TimelineEvent> secondTimeline = reader.readTimeline(secondPath);

        assertEquals(List.of(new TimelineEvent.PlayerQuit(0, "first")), firstTimeline);
        assertEquals(List.of(new TimelineEvent.PlayerQuit(0, "second")), secondTimeline);
    }

    @Test
    void persistsHeaderMetadataAheadOfRecords() throws Exception {
        Path path = tempDir.resolve("header.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path, RECORDING_STARTED_AT)) {
            writer.append(new TimelineEvent.PlayerQuit(0, "uuid-1"));
            writer.flush();
        }

        BinaryReplayAppendLogRecovery recovery = reader.recover(path);

        assertEquals(RECORDING_STARTED_AT, recovery.header().recordingStartedAtEpochMillis());
        assertEquals(List.of(new TimelineEvent.PlayerQuit(0, "uuid-1")), recovery.timeline());
    }
}