package me.justindevb.replay.api;

import me.justindevb.replay.ReplaySession;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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

    /**
     * Start a replay
     * @param viewer
     * @return
     */
    CompletableFuture<Optional<ReplaySession>> startReplay(String replayName, Player viewer);

    /**
     * Stop a replay
     * @param replaySession
     * @return
     */
    boolean stopReplay(Object replaySession);

    /**
     * Collection of all active replays
     * @return
     */
    Collection<?> getActiveReplays();

    /**
     * List of all saved replays
     * @return
     */
    CompletableFuture<List<String>> listSavedReplays();

    /**
     * Delete a saved replay.
     *
     * @param name replay name
     * @return true if deleted, false if replay did not exist or delete failed
     */
    CompletableFuture<Boolean> deleteSavedReplay(String name);

    /**
     * Get a replay file
     * @param name
     * @return
     */
    CompletableFuture<Optional<File>> getSavedReplayFile(String name);

}
