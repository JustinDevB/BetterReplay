package me.justindevb.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.tcoded.folialib.FoliaLib;
import org.bstats.bukkit.Metrics;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import me.justindevb.replay.api.ReplayAPI;
import me.justindevb.replay.benchmark.ReplayBenchmarkCommand;
import me.justindevb.replay.benchmark.ReplayBenchmarkHarness;
import me.justindevb.replay.benchmark.ReplayBenchmarkReportWriter;
import me.justindevb.replay.benchmark.ReplayBenchmarkService;
import me.justindevb.replay.config.ReplayConfigManager;
import me.justindevb.replay.config.ReplayConfigSetting;
import me.justindevb.replay.debug.ReplayDebugCommand;
import me.justindevb.replay.export.ReplayExportCommand;
import me.justindevb.replay.listeners.PacketEventsListener;
import me.justindevb.replay.retention.ReplayRetentionService;
import me.justindevb.replay.retention.RetentionPolicy;
import me.justindevb.replay.util.ReplayCache;
import me.justindevb.replay.util.UpdateChecker;
import me.justindevb.replay.storage.FileReplayStorage;
import me.justindevb.replay.storage.MySQLConnectionManager;
import me.justindevb.replay.storage.MySQLReplayStorage;
import me.justindevb.replay.storage.ReplayStorage;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.logging.Level;

public class Replay extends JavaPlugin {
    private static Replay instance;
    private RecorderManager recorderManager;
    private ReplayStorage storage = null;
    private MySQLConnectionManager connectionManager;
    private ReplayCache replayCache;
    private ReplayManagerImpl manager;
    private FoliaLib foliaLib;
    private ReplayBenchmarkService replayBenchmarkService;
    private ReplayRetentionService replayRetentionService;

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
        manager = new ReplayManagerImpl(this, recorderManager);
        initConfig();
        replayBenchmarkService = createReplayBenchmarkService();
        ReplayCommand replayCommand = new ReplayCommand(manager,
            new ReplayBenchmarkCommand(replayBenchmarkService, foliaLib, getLogger()),
            new ReplayExportCommand(manager, foliaLib, getLogger()),
            new ReplayDebugCommand(this, manager, foliaLib, getLogger()));

        PluginCommand cmd = getCommand("replay");
        if (cmd != null) {
            cmd.setExecutor(replayCommand);
            cmd.setTabCompleter(replayCommand);
        }

        //Initialize API
        ReplayAPI.init(manager);

        initStorage();
        initRetention();
        recorderManager.recoverPendingAppendLogs();

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

        if (replayRetentionService != null)
            replayRetentionService.stop();

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
        new ReplayConfigManager(this).initialize();
    }

    private void checkForUpdate() {
        if (!ReplayConfigSetting.CHECK_UPDATE.getBoolean(getConfig()))
            return;

        String currentVersion = getPluginMeta().getVersion();
        new UpdateChecker(this, "betterreplay").checkForUpdate(currentVersion, result -> {
            if (result.updateAvailable()) {
                String suffix = "release".equals(result.versionType()) ? "" : " (" + result.versionType() + ")";
                getLogger().log(Level.INFO, "Update available: v" + result.latestVersion() + suffix
                        + " — https://modrinth.com/plugin/betterreplay");
            } else {
                getLogger().log(Level.INFO, "You are up to date!");
            }
        });
    }

    private void initStorage() {
        FileConfiguration config = getConfig();
        String storageType = ReplayConfigSetting.STORAGE_TYPE.getString(config).toLowerCase(Locale.ROOT);
        if (storageType.contentEquals("mysql")) {
            String host = ReplayConfigSetting.MYSQL_HOST.getString(config);
            int port = ReplayConfigSetting.MYSQL_PORT.getInt(config);
            String database = ReplayConfigSetting.MYSQL_DATABASE.getString(config);
            String user = ReplayConfigSetting.MYSQL_USER.getString(config);
            String password = ReplayConfigSetting.MYSQL_PASSWORD.getString(config);

            connectionManager = new MySQLConnectionManager(host, port, database, user, password);

            storage = new MySQLReplayStorage(connectionManager.getDataSource(), this);
        } else if (storageType.contentEquals("file")) {
            storage = new FileReplayStorage(this);
        } else {
            getLogger().log(Level.SEVERE, "Invalid storage selected: " + storageType);
            getLogger().log(Level.SEVERE, "Valid types: file, mysql");
            getLogger().log(Level.SEVERE, "Defaulting to file");
            storage = new FileReplayStorage(this);
        }

        replayCache = new ReplayCache();
        getReplayStorage().listReplays().thenAccept(replays -> replayCache.setReplays(replays));
    }

    private void initRetention() {
        RetentionPolicy policy = RetentionPolicy.fromConfig(getConfig(), getLogger());
        replayRetentionService = new ReplayRetentionService(getReplayStorage(), foliaLib, getLogger(), policy, replayCache);
        replayRetentionService.start();
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

    private ReplayBenchmarkService createReplayBenchmarkService() {
        Executor asyncExecutor = runnable -> foliaLib.getScheduler().runAsync(task -> runnable.run());
        return new ReplayBenchmarkService(
                new ReplayBenchmarkHarness(getPluginMeta().getVersion()),
                new ReplayBenchmarkReportWriter(Path.of(getDataFolder().getPath(), "benchmarks")),
                asyncExecutor);
    }
}