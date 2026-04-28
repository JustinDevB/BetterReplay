package me.justindevb.replay.storage.binary;

import me.justindevb.replay.recording.TimelineEvent;

import java.util.List;

/**
 * Result of scanning an append-log temp file and keeping the longest valid prefix.
 */
public record BinaryReplayAppendLogRecovery(
    BinaryReplayAppendLogHeader header,
        List<BinaryReplayAppendLogReader.DecodedRecord> records,
        List<TimelineEvent> timeline,
        List<String> stringTable,
        BinaryReplayRecoveryStopReason stopReason,
        int consumedBytes
) {

    public BinaryReplayAppendLogRecovery {
    header = header == null ? BinaryReplayAppendLogHeader.empty() : header;
        records = List.copyOf(records);
        timeline = List.copyOf(timeline);
        stringTable = List.copyOf(stringTable);
    }

    public boolean isComplete() {
        return stopReason == BinaryReplayRecoveryStopReason.CLEAN_EOF;
    }

    public boolean discardedTail() {
        return !isComplete();
    }
}