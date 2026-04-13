package me.justindevb.replay.util;


import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.ReplayStorage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ReplayObject {

    private final String name;
    private final ReplayStorage storage;
    private List<TimelineEvent> timeline;

    public ReplayObject(String name, List<TimelineEvent> timeline, ReplayStorage storage) {
        this.name = name;
        this.timeline = timeline;
        this.storage = storage;
    }

    public String getName() {
        return name;
    }

    public List<TimelineEvent> getTimeline() {
        return timeline;
    }

    public void setTimeline(List<TimelineEvent> timeline) {
        this.timeline = timeline;
    }

    /** Saves this replay using the configured storage asynchronously */
    public CompletableFuture<Void> save() {
        return storage.saveReplay(name, timeline);
    }

    /** Loads the timeline from storage and updates this object */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Void> load() {
        return storage.loadReplay(name)
                .thenAccept(loaded -> {
                    if (loaded != null) {
                        this.timeline = loaded;
                    }
                });
    }

    /** Deletes this replay from storage */
    public CompletableFuture<Boolean> delete() {
        return storage.deleteReplay(name);
    }

    /** Check if this replay exists in storage */
    public CompletableFuture<Boolean> exists() {
        return storage.replayExists(name);
    }
}
