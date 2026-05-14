package me.justindevb.replay.storage;

import me.justindevb.replay.chunk.ReplayChunkData;
import me.justindevb.replay.recording.TimelineEvent;

import java.util.List;
import java.util.Objects;

/**
 * Playback-ready replay payload including optional chunk archive data.
 */
public record ReplayPlaybackData(List<TimelineEvent> timeline, ReplayChunkData chunkData) {

    public ReplayPlaybackData {
        timeline = List.copyOf(timeline);
        chunkData = Objects.requireNonNull(chunkData, "chunkData");
    }

    public ReplayPlaybackData(List<TimelineEvent> timeline) {
        this(timeline, ReplayChunkData.NONE);
    }
}