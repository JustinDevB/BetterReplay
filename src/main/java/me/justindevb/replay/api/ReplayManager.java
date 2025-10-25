package me.justindevb.replay.api;

import org.bukkit.entity.Player;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReplayManager {

    /**
     * Starts recording a new replay session.
     *
     * @param name The session name
     * @param players The players to record
     * @param durationSeconds Duration in seconds (-1 for infinite)
     * @return The active ReplaySession
     */
    void startRecording(String name, Collection<Player> players, int durationSeconds);

    /**
     * Stops a running recording
     *
     * @param name The session name
     * @param save Whether to save the recording
     * @return true if successfully stopped
     */
    boolean stopRecording(String name, boolean save);

    /**
     * Get all currently running recording sessions.
     */
    Collection<?> getActiveRecordings();

    Optional<?> startReplay(File replayFile, Player viewer);
    boolean stopReplay(Object replaySession);
    Collection<?> getActiveReplays();

    List<String> listSavedReplays();
    Optional<File> getSavedReplayFile(String name);
}
