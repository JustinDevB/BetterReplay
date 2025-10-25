package me.justindevb.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import me.justindevb.replay.api.ReplayAPI;
import me.justindevb.replay.listeners.PacketEventsListener;
import me.justindevb.replay.util.ReplayStorage;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class Replay extends JavaPlugin {
    private static Replay instance;
    private RecorderManager recorderManager;
    private ReplayStorage replayStorage;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();

        PacketEvents.getAPI().getEventManager().registerListener(new PacketEventsListener(this), PacketListenerPriority.LOWEST);

    }

    @Override
    public void onEnable() {
        instance = this;
        PacketEvents.getAPI().init();

        recorderManager = new RecorderManager(this);
        replayStorage = new ReplayStorage(this);
        ReplayCommand replayCommand = new ReplayCommand(recorderManager);

        PluginCommand cmd = getCommand("replay");
        if (cmd != null) {
            cmd.setExecutor(replayCommand);
            cmd.setTabCompleter(replayCommand);
        }

        //Initialize API
        ReplayAPI.init(new ReplayManagerImpl(this, recorderManager));
    }

    @Override
    public void onDisable() {
        recorderManager.shutdown();
        PacketEvents.getAPI().terminate();
        ReplayAPI.shutdown();


        instance = null;
    }

    public static Replay getInstance() {
        return instance;
    }

    public RecorderManager getRecorderManager() {
        return recorderManager;
    }

    public ReplayStorage getReplayStorage() {
        return replayStorage;
    }
}