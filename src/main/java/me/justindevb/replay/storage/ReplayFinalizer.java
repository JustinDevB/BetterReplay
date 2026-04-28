package me.justindevb.replay.storage;

import me.justindevb.replay.recording.TimelineEvent;

import java.io.IOException;
import java.util.List;

/**
 * Produces a finalized replay artifact from a timeline or recovered append-log state.
 */
public interface ReplayFinalizer {

    byte[] finalizeReplay(String replayName, List<TimelineEvent> timeline, String pluginVersion) throws IOException;
}