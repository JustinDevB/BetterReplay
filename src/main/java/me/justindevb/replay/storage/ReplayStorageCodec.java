package me.justindevb.replay.storage;

import me.justindevb.replay.recording.TimelineEvent;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Encodes and decodes replay payloads independently of the storage backend.
 */
public interface ReplayStorageCodec extends ReplayFinalizer, ReplayArchiveReader {

    ReplayFormat format();

    boolean canDecode(String replayName, byte[] storedBytes);

    String fileExtension(boolean compressionEnabled);

    boolean supportsCompression();

    byte[] encodeTimeline(List<TimelineEvent> timeline, String pluginVersion) throws IOException;

    @Override
    default byte[] finalizeReplay(String replayName, List<TimelineEvent> timeline, String pluginVersion) throws IOException {
        return encodeTimeline(timeline, pluginVersion);
    }

    default byte[] finalizeReplay(
            String replayName,
            List<TimelineEvent> timeline,
            String pluginVersion,
            Long recordingStartedAtEpochMillis
    ) throws IOException {
        return finalizeReplay(replayName, timeline, pluginVersion);
    }

    List<TimelineEvent> decodeTimeline(byte[] storedBytes, String runningVersion) throws IOException;

    default ReplayInspection inspectReplay(String replayName, byte[] storedBytes, String runningVersion) throws IOException {
        List<TimelineEvent> timeline = decodeTimeline(storedBytes, runningVersion);
        return ReplayInspectionBuilder.build(
                replayName,
                format(),
                storedBytes.length,
                storedBytes.length,
                storedBytes.length,
                null,
                null,
                null,
                false,
                0,
                timeline);
    }

    @Override
    default List<TimelineEvent> readTimeline(byte[] payload, String runningVersion) throws IOException {
        return decodeTimeline(payload, runningVersion);
    }

    File writeReplayFile(String replayName, byte[] storedBytes, String runningVersion) throws IOException;
}