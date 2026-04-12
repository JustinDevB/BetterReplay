package me.justindevb.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.tcoded.folialib.FoliaLib;
import org.bstats.bukkit.Metrics;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import me.justindevb.replay.api.ReplayAPI;
import me.justindevb.replay.listeners.PacketEventsListener;
import me.justindevb.replay.util.ReplayCache;
import me.justindevb.replay.util.UpdateChecker;
import me.justindevb.replay.storage.FileReplayStorage;
import me.justindevb.replay.storage.MySQLConnectionManager;
import me.justindevb.replay.storage.MySQLReplayStorage;
import me.justindevb.replay.storage.ReplayStorage;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class Replay extends JavaPlugin {
    private static Replay instance;
    private RecorderManager recorderManager;
    private ReplayStorage storage = null;
    private MySQLConnectionManager connectionManager;
    private ReplayCache replayCache;
    private ReplayManagerImpl manager;
    private FoliaLib foliaLib;

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
        foliaLib = new FoliaLib(this);

        recorderManager = new RecorderManager(this);
        ReplayCommand replayCommand = new ReplayCommand(recorderManager);
        initConfig();

        PluginCommand cmd = getCommand("replay");
        if (cmd != null) {
            cmd.setExecutor(replayCommand);
            cmd.setTabCompleter(replayCommand);
        }

        //Initialize API
        ReplayAPI.init(manager = new ReplayManagerImpl(this, recorderManager));

        initStorage();

        initBstats();


        checkForUpdate();
    }

    @Override
    public void onDisable() {
        recorderManager.shutdown();

        for (ReplaySession session : ReplayRegistry.getActiveSessions()) {
            if (session != null)
                session.stop();
        }

        PacketEvents.getAPI().terminate();
        ReplayAPI.shutdown();

        if (connectionManager != null)
            connectionManager.shutdown();



        instance = null;
    }

    public static Replay getInstance() {
        return instance;
    }

    public RecorderManager getRecorderManager() {
        return recorderManager;
    }

    public ReplayStorage getReplayStorage() {
        return storage;
    }

    private void initConfig() {
        initGeneralConfigSettings();

        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    private void initGeneralConfigSettings() {
        FileConfiguration config = getConfig();
        config.addDefault("General.Check-Update", true);
        config.addDefault("General.Compress-Replays", true);  // GZIP compress replay data
        config.addDefault("General.Storage-Type", "file");  // Valid options: "file","mysql"
        config.addDefault("General.MySQL.host", "host");
        config.addDefault("General.MySQL.port", 3306);
        config.addDefault("General.MySQL.database", "database");
        config.addDefault("General.MySQL.user", "username");
        config.addDefault("General.MySQL.password", "password");
    }

    private void checkForUpdate() {
        if (!getConfig().getBoolean("General.Check-Update"))
            return;
        new UpdateChecker(this, 133445).getVersion(version -> {
            String localVersion = this.getPluginMeta().getVersion().replace("-SNAPSHOT", "");
            if (localVersion.equals(version))
                getLogger().log(Level.INFO, "You are up to date!");
            else
                getLogger().log(Level.INFO, "There is an update available! Download at: https://www.spigotmc.org/resources/betterreplay.133445/");
        });
    }

    private void initStorage() {
        FileConfiguration config = getConfig();
        if (getConfig().getString("General.Storage-Type").contentEquals("mysql")) {
            String host = config.getString("General.MySQL.host");
            int port = config.getInt("General.MySQL.port");
            String database = config.getString("General.MySQL.database");
            String user = config.getString("General.MySQL.user");
            String password = config.getString("General.MySQL.password");

            connectionManager = new MySQLConnectionManager(host, port, database, user, password);

            storage = new MySQLReplayStorage(connectionManager.getDataSource(), this);
        } else if (getConfig().getString("General.Storage-Type").contentEquals("file")) {
            storage = new FileReplayStorage(this);
        } else {
            getLogger().log(Level.SEVERE, "Invalid storage selected: " + getConfig().getString("General.Storage-Type"));
            getLogger().log(Level.SEVERE, "Valid types: file, mysql");
            getLogger().log(Level.SEVERE, "Defaulting to file");
            storage = new FileReplayStorage(this);
        }

        replayCache = new ReplayCache();
        getReplayStorage().listReplays().thenAccept(replays -> replayCache.setReplays(replays));
    }

    public ReplayCache getReplayCache() {
        return replayCache;
    }

    public ReplayManagerImpl getReplayManagerImpl() {
        return manager;
    }

    public void initBstats() {
        int pluginId = 29341;
        new Metrics(this, pluginId);
    }

    public FoliaLib getFoliaLib() {
        return foliaLib;
    }
}