package me.justindevb.replay;

import me.justindevb.replay.api.ReplayManager;
import me.justindevb.replay.util.ReplayObject;
import me.justindevb.replay.util.storage.FileReplayStorage;
import me.justindevb.replay.util.storage.ReplayStorage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ReplayManagerImpl implements ReplayManager {

    private final Replay replay;
    private final RecorderManager recorderManager;
    private final ReplayStorage storage; // generic, file or MySQL

    public ReplayManagerImpl(Replay replay, RecorderManager recorderManager) {
        this.replay = replay;
        this.recorderManager = recorderManager;
        this.storage = Replay.getInstance().getReplayStorage();
    }

    @Override
    public void startRecording(String name, Collection<Player> players, int durationSeconds) {
        recorderManager.startSession(name, players, durationSeconds);
    }

    @Override
    public boolean stopRecording(String name, boolean save) {

        boolean stopped = recorderManager.stopSession(name, save);

        if (stopped && save) {
            storage.listReplays().thenAccept(names ->
                    Replay.getInstance().getReplayCache().setReplays(names)
            ).exceptionally(ex -> {
                ex.printStackTrace();
                return null;
            });
        }

        return stopped;
    }


    @Override
    public Collection<?> getActiveRecordings() {
        return recorderManager.getActiveSessions().keySet();
    }

    @Override
    public CompletableFuture<Optional<ReplaySession>> startReplay(String replayName, Player viewer) {
        if (viewer == null || replayName == null || replayName.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        // Check if the replay exists first
        return replay.getReplayStorage().replayExists(replayName)
                .thenCompose(exists -> {
                    if (!exists) {
                        runSync(() -> viewer.sendMessage("§cReplay not found: " + replayName));
                        return CompletableFuture.completedFuture(Optional.<ReplaySession>empty());
                    }

                    // Replay exists, load the timeline
                    return replay.getReplayStorage().loadReplay(replayName)
                            .thenApply(rawTimeline -> {
                                if (rawTimeline == null || rawTimeline.isEmpty()) {
                                    runSync(() -> viewer.sendMessage("§cReplay is empty or corrupted: " + replayName));
                                    return Optional.<ReplaySession>empty();
                                }

                                List<Map<String, Object>> timeline = castTimeline(rawTimeline);
                                ReplaySession session = new ReplaySession(timeline, viewer, replay);

                                runSync(session::start);

                                return Optional.of(session);
                            });
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    runSync(() -> viewer.sendMessage("§cFailed to start replay: " + replayName));
                    return Optional.empty();
                });
    }

    // Helper to cast List<?> to List<Map<String, Object>>
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castTimeline(List<?> raw) {
        return (List<Map<String, Object>>) (List<?>) raw;
    }

    // Helper to run code on the main thread
    private void runSync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(replay, task);
        }
    }



   /* @Override
    public CompletableFuture<Optional<ReplaySession>> startReplay(String replayName, Player viewer) {
        if (viewer == null || replayName == null || replayName.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        // Load the replay timeline from storage
        return replay.getReplayStorage().loadReplay(replayName)
                .thenApply(rawTimeline -> {
                    if (rawTimeline == null || rawTimeline.isEmpty()) {
                        viewer.sendMessage("Replay not found or empty: " + replayName);
                        return Optional.<ReplaySession>empty();
                    }

                    // Cast each element to Map<String, Object>
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> timeline = (List<Map<String, Object>>) (List<?>) rawTimeline;

                    // Create and start the session
                    ReplaySession session = new ReplaySession(timeline, viewer, replay);
                    session.start();

                    return Optional.of(session);
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    viewer.sendMessage("Failed to start replay: " + replayName);
                    return Optional.empty();
                });
    }
*/


   /* @Override
    public Optional<ReplaySession> startReplay(File replayFile, Player viewer) {
        if (replayFile == null || !replayFile.exists())
            return Optional.empty();

        ReplaySession session = new ReplaySession(replayFile, viewer, replay);
        session.start();

        return Optional.of(session);
    }
    */

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
        // Async-friendly, works for MySQL or files
        return storage.listReplays();
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
                    ex.printStackTrace();
                    return Optional.empty();
                });
    }
}

/*public class ReplayManagerImpl implements ReplayManager {

    private final Replay replay;
    private final RecorderManager recorderManager;
    private final FileReplayStorage fileReplayStorage;

    public ReplayManagerImpl(Replay replay, RecorderManager recorderManager) {
        this.replay = replay;
        this.recorderManager = recorderManager;
        this.fileReplayStorage = Replay.getInstance().getReplayStorage();
    }

    @Override
    public void startRecording(String name, Collection<Player> players, int durationSeconds) {
        recorderManager.startSession(name, players, durationSeconds);
    }

    @Override
    public boolean stopRecording(String name, boolean save) {
        //TODO: Implement save logic
        return recorderManager.stopSession(name, save);
    }

    @Override
    public Collection<?> getActiveRecordings() {
        return recorderManager.getActiveSessions().keySet();
    }

    @Override
    public Optional<?> startReplay(File replayFile, Player viewer) {
        if (replayFile == null || !replayFile.exists())
            return Optional.empty();

        ReplaySession session = new ReplaySession(replayFile, viewer, replay);
        session.start();

        return Optional.of(session);
    }

    @Override
    public boolean stopReplay(Object replaySession) {
        //TODO: Handle stop logic better, implement a way to force a stop including destroying all fake entities
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
    public List<String> listSavedReplays() {
        return fileReplayStorage.listReplays();
    }

    @Override
    public Optional<File> getSavedReplayFile(String name) {
        return Optional.ofNullable(fileReplayStorage.getReplayFile(name));
    }


}
*/