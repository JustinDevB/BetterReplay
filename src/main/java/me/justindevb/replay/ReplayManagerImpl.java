package me.justindevb.replay;

import me.justindevb.replay.api.ReplayExportQuery;
import me.justindevb.replay.api.ReplayManager;
import me.justindevb.replay.storage.ReplayStorage;
import me.justindevb.replay.util.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ReplayManagerImpl implements ReplayManager {

    private final Replay replay;
    private final RecorderManager recorderManager;

    public ReplayManagerImpl(Replay replay, RecorderManager recorderManager) {
        this.replay = replay;
        this.recorderManager = recorderManager;
    }

    @Override
    public boolean startRecording(String name, Collection<Player> players, int durationSeconds) {
        return recorderManager.startSession(name, players, durationSeconds);
    }

    @Override
    public boolean stopRecording(String name, boolean save) {

        boolean stopped = recorderManager.stopSession(name, save);

        if (stopped && save) {
            ReplayStorage storage = replay.getReplayStorage();
            if (storage == null) {
                replay.getLogger().warning("Storage is not initialized yet; skipping replay list refresh.");
                return stopped;
            }

            storage.listReplays().thenAccept(names ->
                    replay.getReplayCache().setReplays(names)
            ).exceptionally(ex -> {
                replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to refresh replay cache", ex);
                return null;
            });
        }

        return stopped;
    }


    @Override
    public Collection<String> getActiveRecordings() {
        return recorderManager.getActiveSessions().keySet();
    }

    @Override
    public CompletableFuture<Optional<ReplaySession>> startReplay(String replayName, Player viewer) {
        if (viewer == null || replayName == null || replayName.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return replay.getReplayStorage().replayExists(replayName)
                .thenCompose(exists -> {
                    if (!exists) {
                        runSync(() -> viewer.sendMessage("§cReplay not found: " + replayName));
                        return CompletableFuture.completedFuture(Optional.<ReplaySession>empty());
                    }

                    return replay.getReplayStorage().loadReplay(replayName)
                            .thenApply(timeline -> {
                                if (timeline == null || timeline.isEmpty()) {
                                    runSync(() -> viewer.sendMessage("§cReplay is empty or corrupted: " + replayName));
                                    return Optional.<ReplaySession>empty();
                                }

                                ReplaySession session = new ReplaySession(timeline, viewer, replay);

                                runSync(session::start);

                                return Optional.of(session);
                            });
                })
                .exceptionally(ex -> {
                    Throwable cause = ex;
                    while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    if (cause instanceof VersionUtil.ReplayVersionMismatchException mismatch) {
                        runSync(() -> viewer.sendMessage("§cThis recording requires BetterReplay v"
                                + mismatch.getRequiredVersion() + "+. You are running v"
                                + mismatch.getRunningVersion() + "."));
                    } else {
                        replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to start replay: " + replayName, ex);
                        runSync(() -> viewer.sendMessage("§cFailed to start replay: " + replayName));
                    }
                    return Optional.empty();
                });
    }

    private void runSync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            replay.getFoliaLib().getScheduler().runLater(task, 1L);
        }
    }

    @Override
    public boolean stopReplay(Object replaySession) {
        if (!(replaySession instanceof ReplaySession session))
            return false;

        session.stop();
        return true;
    }

    @Override
    public Collection<?> getActiveReplays() {
        return ReplayRegistry.getActiveSessions();
    }

    @Override
    public CompletableFuture<List<String>> listSavedReplays() {
        ReplayStorage storage = replay.getReplayStorage();
        if (storage == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return storage.listReplays();
    }

    @Override
    public CompletableFuture<Boolean> deleteSavedReplay(String name) {
        if (name == null || name.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }

        ReplayStorage storage = replay.getReplayStorage();
        if (storage == null) {
            return CompletableFuture.completedFuture(false);
        }

        return storage.deleteReplay(name)
                .thenCompose(deleted -> storage.listReplays()
                        .thenApply(names -> {
                            replay.getReplayCache().setReplays(names);
                            return deleted;
                        }))
                .exceptionally(ex -> {
                    replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to delete replay: " + name, ex);
                    return false;
                });
    }

    @Override
    public List<String> getCachedReplayNames() {
        return replay.getReplayCache().getReplays();
    }

    @Override
    public CompletableFuture<Optional<File>> getSavedReplayFile(String name) {
        return replay.getReplayStorage().getReplayFile(name)
                .thenApply(file -> {
                    if (file == null || !file.exists()) {
                        return Optional.<File>empty();
                    }
                    return Optional.of(file);
                })
                .exceptionally(ex -> {
                    replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to get replay file: " + name, ex);
                    return Optional.empty();
                });
    }

    @Override
    public CompletableFuture<Optional<File>> getSavedReplayFile(String name, ReplayExportQuery query) {
        return replay.getReplayStorage().getReplayFile(name, query)
                .thenApply(file -> {
                    if (file == null || !file.exists()) {
                        return Optional.<File>empty();
                    }
                    return Optional.of(file);
                })
                .exceptionally(ex -> {
                    replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to export replay file: " + name, ex);
                    return Optional.empty();
                });
    }
}
