package me.justindevb.replay.storage;

import me.justindevb.replay.recording.TimelineEvent;

import java.io.IOException;

/**
 * Appends live recording events to an in-progress replay payload.
 */
public interface ReplayAppendLogWriter extends AutoCloseable {

    void append(TimelineEvent event) throws IOException;

    void flush() throws IOException;

    @Override
    void close() throws IOException;
}