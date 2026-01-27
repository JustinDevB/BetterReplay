package me.justindevb.replay;

import me.justindevb.replay.api.events.RecordingStartEvent;
import me.justindevb.replay.api.events.RecordingStopEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;

public class RecorderManager {
    private final Replay replay;
    private final Map<String, RecordingSession> activeSessions = new HashMap<>();
    private BukkitTask tickTask;

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
            tickTask = Bukkit.getScheduler().runTaskTimer(replay, this::tickAll, 1L, 1L);
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
                .thenAccept(rawTimeline -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> timeline = (List<Map<String, Object>>) rawTimeline;

                    Bukkit.getScheduler().runTask(replay, () -> {
                        new ReplaySession(timeline, viewer, replay).start();
                    });
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    viewer.sendMessage("§cFailed to load replay: " + name);
                    return null;
                });
    }


 /*   public void replaySession(String name, Player viewer) {
        File file = new File(replay.getDataFolder(), "replays/" + name + ".json");
        if (!file.exists()) {
            viewer.sendMessage("Replay not found: " + name);
            return;
        }
        new ReplaySession(file, viewer, replay).start();
    }
  */

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
