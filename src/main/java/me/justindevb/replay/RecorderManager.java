package me.justindevb.replay;

import com.tcoded.folialib.wrapper.task.WrappedTask;
import me.justindevb.replay.api.events.RecordingStartEvent;
import me.justindevb.replay.api.events.RecordingStopEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

public class RecorderManager {
    private final Replay replay;
    private final Map<String, RecordingSession> activeSessions = new HashMap<>();
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

        Bukkit.getPluginManager().callEvent(new RecordingStopEvent(session));

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
                    ex.printStackTrace();
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
