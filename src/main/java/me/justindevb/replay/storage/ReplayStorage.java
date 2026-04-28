package me.justindevb.replay.storage;

import me.justindevb.replay.api.ReplayExportQuery;
import me.justindevb.replay.debug.ReplayDumpQuery;
import me.justindevb.replay.recording.TimelineEvent;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ReplayStorage {

    CompletableFuture<Void> saveReplay(String name, List<TimelineEvent> timeline);

    default CompletableFuture<Void> saveReplay(String name, ReplaySaveRequest request) {
        return saveReplay(name, request.timeline());
    }

    CompletableFuture<List<TimelineEvent>> loadReplay(String name);

    CompletableFuture<List<String>> listReplays();

    CompletableFuture<Boolean> deleteReplay(String name);

    CompletableFuture<Boolean> replayExists(String name);

    CompletableFuture<File> getReplayFile(String name);

    default CompletableFuture<File> getReplayFile(String name, ReplayExportQuery query) {
        return getReplayFile(name);
    }

    default CompletableFuture<ReplayInspection> getReplayInfo(String name) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Replay info is not supported by this storage backend"));
    }

    default CompletableFuture<File> getReplayDumpFile(String name, ReplayDumpQuery query) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Replay dump is not supported by this storage backend"));
    }
}

