package me.justindevb.replay;

import com.tcoded.folialib.wrapper.task.WrappedTask;
import me.justindevb.replay.api.events.RecordingStartEvent;
import me.justindevb.replay.api.events.RecordingStopEvent;
import me.justindevb.replay.storage.ReplaySaveRequest;
import me.justindevb.replay.storage.binary.BinaryReplayAppendLogReader;
import me.justindevb.replay.storage.binary.BinaryReplayAppendLogRecovery;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class RecorderManager {
    private static final String APPEND_LOG_EXTENSION = ".appendlog";

    private final Replay replay;
    private final ConcurrentHashMap<String, RecordingSession> activeSessions = new ConcurrentHashMap<>();
    private final BinaryReplayAppendLogReader appendLogReader = new BinaryReplayAppendLogReader();
    private WrappedTask tickTask;

    public RecorderManager(Replay replay) {
        this.replay = replay;
    }

    public boolean startSession(String name, Collection<Player> players, int durationSeconds) {
        if (activeSessions.containsKey(name)) {
            return false;
        }

        RecordingSession session = new RecordingSession(name, replay.getDataFolder(), players, durationSeconds);
        session.start();

        Bukkit.getPluginManager().callEvent(new RecordingStartEvent(name, players, session, durationSeconds));
        activeSessions.put(name, session);

        if (tickTask == null) {
            tickTask = replay.getFoliaLib().getScheduler().runTimer(this::tickAll, 1L, 1L);
        }
        return true;
    }


    public boolean stopSession(String name, boolean save) {
        RecordingSession session = activeSessions.remove(name);
        if (session == null)
            return false;

        session.stop(save);

        replay.getFoliaLib().getScheduler().runNextTick(task -> {
            Bukkit.getPluginManager().callEvent(new RecordingStopEvent(session));
        });

        if (activeSessions.isEmpty() && tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        return true;
    }

    private void tickAll() {
        Iterator<Map.Entry<String, RecordingSession>> it = activeSessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RecordingSession> entry = it.next();
            RecordingSession session = entry.getValue();
            session.tick();
            if (session.isStopped()) {
                it.remove();
            }
        }

        if (activeSessions.isEmpty() && tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    public Map<String, RecordingSession> getActiveSessions() {
        return activeSessions;
    }

    public void recoverPendingAppendLogs() {
        File tempFolder = new File(replay.getDataFolder(), "replays/.tmp");
        File[] appendLogs = tempFolder.listFiles((dir, name) -> name.endsWith(APPEND_LOG_EXTENSION));
        if (appendLogs == null || appendLogs.length == 0) {
            return;
        }

        replay.getLogger().info("Found " + appendLogs.length + " pending replay temp log(s) to recover.");
        for (File appendLog : appendLogs) {
            recoverAppendLog(appendLog);
        }
    }

    private void recoverAppendLog(File appendLogFile) {
        String replayName = appendLogFile.getName().substring(0, appendLogFile.getName().length() - APPEND_LOG_EXTENSION.length());

        BinaryReplayAppendLogRecovery recovery;
        try {
            recovery = appendLogReader.recover(appendLogFile.toPath());
        } catch (IOException e) {
            replay.getLogger().log(Level.SEVERE, "Failed to recover recording temp log: " + appendLogFile.getName(), e);
            return;
        }

        if (recovery.timeline().isEmpty()) {
            replay.getLogger().warning("Skipping recovery for " + replayName + ": no valid events found (" + recovery.stopReason() + ")");
            return;
        }

        if (recovery.discardedTail()) {
            replay.getLogger().warning("Recovered replay " + replayName + " with truncated tail: " + recovery.stopReason());
        }

        long recoveredStart = recovery.header().recordingStartedAtEpochMillis() > 0
                ? recovery.header().recordingStartedAtEpochMillis()
                : System.currentTimeMillis();

        replay.getReplayStorage().saveReplay(replayName, new ReplaySaveRequest(recovery.timeline(), recoveredStart))
                .thenCompose(v -> refreshReplayCache().thenApply(ignored -> v))
                .thenAccept(v -> {
                    if (appendLogFile.exists() && !appendLogFile.delete()) {
                        replay.getLogger().warning("Recovered replay " + replayName + " but failed to delete temp log " + appendLogFile.getName());
                        return;
                    }
                    replay.getLogger().info("Recovered replay from temp log: " + replayName);
                })
                .exceptionally(ex -> {
                    replay.getLogger().log(Level.SEVERE, "Failed to save recovered replay: " + replayName, ex);
                    return null;
                });
    }

    private CompletableFuture<Void> refreshReplayCache() {
        return replay.getReplayStorage().listReplays().thenAccept(replays -> replay.getReplayCache().setReplays(replays));
    }

    @Deprecated
    public void replaySession(String name, Player viewer) {
        replay.getReplayStorage().loadReplay(name)
                .thenAccept(timeline -> {
                   // Bukkit.getScheduler().runTask(replay, () -> {
                    replay.getFoliaLib().getScheduler().runNextTick(task -> {
                        new ReplaySession(timeline, viewer, replay).start();
                    });
                })
                .exceptionally(ex -> {
                    replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to load replay: " + name, ex);
                    viewer.sendMessage("§cFailed to load replay: " + name);
                    return null;
                });
    }


    public void shutdown() {
        for (RecordingSession s : activeSessions.values())
            s.stop(false);

        activeSessions.clear();
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }
}
