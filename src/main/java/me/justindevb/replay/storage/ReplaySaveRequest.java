package me.justindevb.replay.storage;

import me.justindevb.replay.recording.TimelineEvent;

import java.util.List;

/**
 * Storage-bound replay payload plus optional metadata captured during recording.
 */
public record ReplaySaveRequest(
        List<TimelineEvent> timeline,
        Long recordingStartedAtEpochMillis
) {

    public ReplaySaveRequest {
        timeline = List.copyOf(timeline);
        if (recordingStartedAtEpochMillis != null && recordingStartedAtEpochMillis < 0) {
            throw new IllegalArgumentException("recordingStartedAtEpochMillis must be non-negative");
        }
    }

    public ReplaySaveRequest(List<TimelineEvent> timeline) {
        this(timeline, null);
    }
}