package me.justindevb.replay.api;

import me.justindevb.replay.ReplaySession;
import me.justindevb.replay.storage.ReplayDeleteResult;
import me.justindevb.replay.storage.ReplayProtectionResult;
import me.justindevb.replay.storage.ReplaySummary;
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
     * @return true if the session was started, false if a session with that name already exists
     */
    boolean startRecording(String name, Collection<Player> players, int durationSeconds);

    /**
     * Stops a running recording
     *
     * @param name The session name
     * @param save Whether to save the recording
     * @return true if successfully stopped
     */
    boolean stopRecording(String name, boolean save);

    /**
     * Get the names of all currently running recording sessions.
     */
    Collection<String> getActiveRecordings();

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
     * List metadata for all saved replays.
     *
     * @return replay summaries for administrative and retention flows
     */
    CompletableFuture<List<ReplaySummary>> listSavedReplaySummaries();

    /**
     * Delete a saved replay.
     *
     * @param name replay name
     * @return explicit delete result
     */
    CompletableFuture<ReplayDeleteResult> deleteSavedReplay(String name);

    /**
     * Protect a saved replay from deletion.
     *
     * @param name replay name
     * @param protectedBy actor who enabled protection
     * @return explicit protection update result
     */
    CompletableFuture<ReplayProtectionResult> protectSavedReplay(String name, String protectedBy);

    /**
     * Remove deletion protection from a saved replay.
     *
     * @param name replay name
     * @return explicit protection update result
     */
    CompletableFuture<ReplayProtectionResult> unprotectSavedReplay(String name);

    /**
     * Get a cached snapshot of saved replay names for synchronous access
     * (e.g. tab completion). The cache is refreshed automatically after
     * saves and deletes.
     */
    List<String> getCachedReplayNames();

    /**
     * Get a replay file
     * @param name
     * @return
     */
    CompletableFuture<Optional<File>> getSavedReplayFile(String name);

    /**
     * Export a replay file with optional player and tick-range filters.
     *
     * @param name replay name
     * @param query export filters
     * @return optional exported file
     */
    default CompletableFuture<Optional<File>> getSavedReplayFile(String name, ReplayExportQuery query) {
        return getSavedReplayFile(name);
    }

}
