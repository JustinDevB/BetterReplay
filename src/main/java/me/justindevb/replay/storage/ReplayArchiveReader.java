package me.justindevb.replay.storage;

import me.justindevb.replay.recording.TimelineEvent;

import java.io.IOException;
import java.util.List;

/**
 * Reads a finalized replay artifact for playback-oriented consumers.
 */
public interface ReplayArchiveReader {

    List<TimelineEvent> readTimeline(byte[] payload, String runningVersion) throws IOException;
}