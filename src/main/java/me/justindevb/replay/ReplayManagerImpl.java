package me.justindevb.replay;

import me.justindevb.replay.api.ReplayManager;
import me.justindevb.replay.util.ReplayStorage;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public class ReplayManagerImpl implements ReplayManager {

    private final Replay replay;
    private final RecorderManager recorderManager;
    private final ReplayStorage replayStorage;

    public ReplayManagerImpl(Replay replay, RecorderManager recorderManager) {
        this.replay = replay;
        this.recorderManager = recorderManager;
        this.replayStorage = Replay.getInstance().getReplayStorage();
    }

    @Override
    public void startRecording(String name, Collection<Player> players, int durationSeconds) {
        recorderManager.startSession(name, players, durationSeconds);
    }

    @Override
    public boolean stopRecording(String name, boolean save) {
        //TODO: Implement save logic
        return recorderManager.stopSession(name);
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
        return replayStorage.listReplays();
    }

    @Override
    public Optional<File> getSavedReplayFile(String name) {
        return Optional.ofNullable(replayStorage.getReplayFile(name));
    }


}
