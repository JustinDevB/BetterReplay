package me.justindevb.replay.storage;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ReplayStorage {

    CompletableFuture<Void> saveReplay(String name, List<?> timeline);

    CompletableFuture<List<?>> loadReplay(String name);

    CompletableFuture<List<String>> listReplays();

    CompletableFuture<Boolean> deleteReplay(String name);

    CompletableFuture<Boolean> replayExists(String name);

    CompletableFuture<File> getReplayFile(String name);
}

