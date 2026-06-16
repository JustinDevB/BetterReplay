package me.justindevb.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import me.justindevb.replay.chunk.ChunkBaselineCaptureService;
import me.justindevb.replay.chunk.ChunkCaptureConfig;
import me.justindevb.replay.chunk.ChunkCaptureCoordinator;
import me.justindevb.replay.chunk.ChunkCoordinate;
import me.justindevb.replay.chunk.ChunkRecordingArtifacts;
import me.justindevb.replay.chunk.FoliaRegionChunkBaselineCaptureService;
import me.justindevb.replay.chunk.RadiusChunkInterestTracker;
import me.justindevb.replay.chunk.WorldChunkPacketFriendlyCaptureService;
import me.justindevb.replay.recording.EntityTracker;
import me.justindevb.replay.recording.FoliaTrackedChunkCollector;
import me.justindevb.replay.recording.RecordingEventHandler;
import me.justindevb.replay.recording.RecordingPacketHandler;
import me.justindevb.replay.recording.TimelineBuilder;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.recording.inventory.InventoryCaptureService;
import me.justindevb.replay.recording.inventory.InventoryCaptureService.CapturedEquipmentState;
import me.justindevb.replay.recording.inventory.InventoryCaptureService.CapturedInventoryStorageSnapshot;
import me.justindevb.replay.recording.inventory.SharedEquipmentCaptureCache;
import me.justindevb.replay.recording.inventory.SharedStorageCaptureCache;
import me.justindevb.replay.storage.ReplaySaveRequest;
import me.justindevb.replay.storage.binary.BinaryReplayAppendLogHeader;
import me.justindevb.replay.storage.binary.BinaryReplayAppendLogRecovery;
import me.justindevb.replay.storage.binary.BinaryReplayAppendLogReader;
import me.justindevb.replay.storage.binary.BinaryReplayAppendLogWriter;
import me.justindevb.replay.storage.binary.BinaryPacketFriendlyChunkPayloadCodec;
import me.justindevb.replay.storage.binary.BinaryChunkTempRegionFileWriter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.HandlerList;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinates a recording session. Owns the tick loop and delegates event handling,
 * entity tracking, and timeline building to focused components in the recording package.
 */
public class RecordingSession {

    private static final int APPEND_LOG_FLUSH_INTERVAL_TICKS = 20;

    private final Replay replay;
    private final String name;
    private final File appendLogFile;
    private final File chunkCaptureDirectory;
    private final long recordingStartedAtEpochMillis;

    private final EntityTracker tracker;
    private final TimelineBuilder builder;
    private final BinaryReplayAppendLogWriter appendLogWriter;
    private final BinaryReplayAppendLogReader appendLogReader;
    private final RecordingEventHandler eventHandler;
    private final RecordingPacketHandler packetHandler;
    private final ChunkCaptureConfig chunkCaptureConfig;
    private final ChunkCaptureCoordinator chunkCaptureCoordinator;
    private final TrackedChunkCollector trackedChunkCollector;
    private final SharedStorageCaptureCache sharedStorageCaptureCache;
    private PacketListenerCommon packetListenerHandle;

    private static final int INVENTORY_CHECK_INTERVAL = 5;
    private static final int CLEAN_INVENTORY_SWEEP_INTERVAL = 20;
    private static final int CLEAN_EQUIPMENT_SWEEP_INTERVAL = 20;
    private final InventoryCaptureService inventoryCaptureService = new InventoryCaptureService();
    private final SharedEquipmentCaptureCache standaloneEquipmentCaptureCache = new SharedEquipmentCaptureCache();
    private final Map<UUID, CapturedInventoryStorageSnapshot> lastInventoryStorageSnapshot = new HashMap<>();
    private final Map<UUID, CapturedEquipmentState> lastEquipmentState = new HashMap<>();
    private final Set<UUID> inventoryDirtyPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> equipmentDirtyPlayers = ConcurrentHashMap.newKeySet();
    private int tick = 0;
    private int durationTicks = -1;
    private boolean stopped = false;
    private boolean chunkCaptureFailed = false;

    public RecordingSession(String name, File folder, Collection<Player> players, int durationSeconds) {
        this(name, folder, players, durationSeconds, new SharedStorageCaptureCache());
    }

    RecordingSession(String name,
                     File folder,
                     Collection<Player> players,
                     int durationSeconds,
                     SharedStorageCaptureCache sharedStorageCaptureCache) {
        this.name = name;
        this.durationTicks = durationSeconds > 0 ? durationSeconds * 20 : -1;
        this.replay = Replay.getInstance();
        this.recordingStartedAtEpochMillis = System.currentTimeMillis();
        this.appendLogFile = new File(folder, "replays/.tmp/" + name + ".appendlog");
        this.chunkCaptureDirectory = new File(folder, "replays/.tmp/chunks/" + name);
        this.appendLogReader = new BinaryReplayAppendLogReader();
        this.sharedStorageCaptureCache = sharedStorageCaptureCache;
        FileConfiguration config = replay.getConfig();
        this.chunkCaptureConfig = config != null ? ChunkCaptureConfig.from(config) : ChunkCaptureConfig.disabled();
        this.trackedChunkCollector = createTrackedChunkCollector();

        try {
            this.appendLogWriter = new BinaryReplayAppendLogWriter(
                    appendLogFile.toPath(),
                    new BinaryReplayAppendLogHeader(recordingStartedAtEpochMillis));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create recording append-log for " + name, e);
        }

        try {
            this.chunkCaptureCoordinator = chunkCaptureConfig.enabled()
                    ? new ChunkCaptureCoordinator(
                            chunkCaptureConfig,
                            new RadiusChunkInterestTracker(),
                            createChunkBaselineCaptureService(),
                            new BinaryChunkTempRegionFileWriter(chunkCaptureDirectory.toPath()))
                    : null;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create chunk capture workspace for " + name, e);
        }

        this.tracker = new EntityTracker(players);
        this.builder = new TimelineBuilder(appendLogWriter, false);
        this.eventHandler = new RecordingEventHandler(tracker, builder, this::getTick, this::markInventoryDirty, this::markEquipmentDirty);
        this.packetHandler = new RecordingPacketHandler(
            tracker,
            builder,
            this::getTick,
            runnable -> replay.getFoliaLib().getScheduler().runNextTick(task -> runnable.run()));
    }

    public void start() {
        if (!appendLogFile.getParentFile().exists()) appendLogFile.getParentFile().mkdirs();

        Bukkit.getLogger().info("Started recording: " + name + " for " + tracker.getTrackedPlayers().size()
                + " player(s), duration=" + (durationTicks == -1 ? "∞" : durationTicks / 20 + "s"));

        Bukkit.getPluginManager().registerEvents(eventHandler, replay);
        packetListenerHandle = PacketEvents.getAPI().getEventManager().registerListener(packetHandler, PacketListenerPriority.NORMAL);

        captureInitialInventory();
    }

    /** Called every tick by RecorderManager */
    public void tick() {
        standaloneEquipmentCaptureCache.beginTick();
        tick(standaloneEquipmentCaptureCache);
    }

    void tick(SharedEquipmentCaptureCache equipmentCaptureCache) {
        if (stopped) return;

        if (durationTicks != -1 && tick >= durationTicks) {
            stop(true);
            return;
        }

        for (UUID uuid : tracker.getTrackedPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;

            Location loc = p.getLocation();

            builder.addEvent(new TimelineEvent.PlayerMove(
                    tick,
                    uuid.toString(),
                    p.getName(),
                    p.getWorld().getName(),
                    loc.getX(), loc.getY(), loc.getZ(),
                    loc.getYaw(), loc.getPitch(),
                    p.getPose().name()
            ));
        }

        for (Map.Entry<UUID, EntityType> entry : tracker.getTrackedEntities().entrySet()) {
            UUID uuid = entry.getKey();
            Entity e = Bukkit.getEntity(uuid);
            if (e == null || e.isDead()) continue;

            builder.addEvent(new TimelineEvent.EntityMove(
                    tick,
                    uuid.toString(),
                    e.getType().name(),
                    e.getWorld().getName(),
                    e.getLocation().getX(), e.getLocation().getY(), e.getLocation().getZ(),
                    e.getLocation().getYaw(), e.getLocation().getPitch()
            ));
        }

        tickEquipmentCheck(equipmentCaptureCache, tick % CLEAN_EQUIPMENT_SWEEP_INTERVAL == 0);

        if (tick % INVENTORY_CHECK_INTERVAL == 0) {
            tickInventoryCheck(tick % CLEAN_INVENTORY_SWEEP_INTERVAL == 0);
        }

        if (!chunkCaptureFailed
                && chunkCaptureCoordinator != null
                && tick % chunkCaptureConfig.captureIntervalTicks() == 0) {
            captureChunkBaselines();
        }

        if ((tick + 1) % APPEND_LOG_FLUSH_INTERVAL_TICKS == 0) {
            flushAppendLog();
        }

        tick++;
    }

    private void tickEquipmentCheck(SharedEquipmentCaptureCache equipmentCaptureCache, boolean includeCleanPlayers) {
        for (UUID uuid : tracker.getTrackedPlayers()) {
            if (!includeCleanPlayers && !equipmentDirtyPlayers.contains(uuid)) continue;

            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;

            CapturedEquipmentState currentEquipment = equipmentCaptureCache.captureEquipment(p, inventoryCaptureService);
            CapturedEquipmentState previousEquipment = lastEquipmentState.get(uuid);
            equipmentDirtyPlayers.remove(uuid);
            if (!inventoryCaptureService.hasEquipmentChanged(currentEquipment, previousEquipment)) {
                continue;
            }

            lastEquipmentState.put(uuid, currentEquipment);
            builder.addEvent(inventoryCaptureService.toEquipmentEvent(tick, uuid.toString(), currentEquipment));
        }
    }

    private void tickInventoryCheck(boolean includeCleanPlayers) {
        for (UUID uuid : tracker.getTrackedPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;
            if (!includeCleanPlayers && !inventoryDirtyPlayers.contains(uuid)) continue;

            boolean forceFreshCapture = includeCleanPlayers && !inventoryDirtyPlayers.contains(uuid);
            CapturedInventoryStorageSnapshot currentStorage = sharedStorageCaptureCache.captureStorage(
                    p,
                    tick,
                    INVENTORY_CHECK_INTERVAL,
                    forceFreshCapture,
                    inventoryCaptureService);
            CapturedInventoryStorageSnapshot previousStorage = lastInventoryStorageSnapshot.get(uuid);
            if (inventoryCaptureService.hasStorageChanged(currentStorage, previousStorage)) {
                lastInventoryStorageSnapshot.put(uuid, currentStorage);
                builder.addEvent(inventoryCaptureService.toStorageEvent(tick, uuid.toString(), currentStorage));
            }

            inventoryDirtyPlayers.remove(uuid);
        }
    }

    public void stop(boolean save) {
        if (stopped) return;
        stopped = true;
        HandlerList.unregisterAll(eventHandler);
        if (packetListenerHandle != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListenerHandle);
            packetListenerHandle = null;
        }

        tracker.clearPlayers();

        closeAppendLog();
        ChunkRecordingArtifacts chunkArtifacts = closeChunkCapture();

        if (!save) {
            deleteAppendLog();
            deleteChunkCaptureDirectory();
            return;
        }

        BinaryReplayAppendLogRecovery recovery;
        try {
            recovery = appendLogReader.recover(appendLogFile.toPath());
            if (!recovery.isComplete()) {
                throw new IOException("Append-log ended with " + recovery.stopReason());
            }
        } catch (IOException e) {
            replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to read recording temp log: " + name, e);
            return;
        }

        long recoveredStart = recovery.header().recordingStartedAtEpochMillis() > 0
            ? recovery.header().recordingStartedAtEpochMillis()
            : recordingStartedAtEpochMillis;

        replay.getReplayStorage().saveReplay(name, new ReplaySaveRequest(recovery.timeline(), recoveredStart, chunkArtifacts))
                .thenCompose(v ->
                        replay.getReplayStorage().listReplays()
                )
                .thenAccept(replays -> {
                    deleteAppendLog();
                    deleteChunkCaptureDirectory();
                    replay.getReplayCache().setReplays(replays);
                    replay.getLogger().info("Recording " + name + " saved!");
                })
                .exceptionally(ex -> {
                    replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to save recording: " + name, ex);
                    return null;
                });
    }

    public boolean isStopped() {
        return stopped;
    }

    public int getTick() {
        return tick;
    }

    public List<TimelineEvent> getTimeline() {
        flushAppendLog();
        try {
            return appendLogReader.readTimeline(appendLogFile.toPath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read recording temp log for " + name, e);
        }
    }

    public Set<UUID> getTrackedPlayers() {
        return tracker.getTrackedPlayers();
    }

    public boolean isTrackedPlayer(UUID uuid) {
        return tracker.isTrackedPlayer(uuid);
    }

    private void captureInitialInventory() {
        for (UUID uuid : tracker.getTrackedPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;

            CapturedEquipmentState equipment = inventoryCaptureService.captureEquipment(p);
            CapturedInventoryStorageSnapshot storage = inventoryCaptureService.captureStorage(p);
            lastEquipmentState.put(uuid, equipment);
            lastInventoryStorageSnapshot.put(uuid, storage);
            builder.addEvent(inventoryCaptureService.toEquipmentEvent(tick, uuid.toString(), equipment));
            builder.addEvent(inventoryCaptureService.toStorageEvent(tick, uuid.toString(), storage));
        }
    }

    private void markInventoryDirty(UUID uuid) {
        if (tracker.isTrackedPlayer(uuid)) {
            inventoryDirtyPlayers.add(uuid);
            sharedStorageCaptureCache.invalidate(uuid);
        }
    }

    private void markEquipmentDirty(UUID uuid) {
        if (tracker.isTrackedPlayer(uuid)) {
            equipmentDirtyPlayers.add(uuid);
        }
    }

    private void flushAppendLog() {
        try {
            appendLogWriter.flush();
        } catch (IOException e) {
            throw new RuntimeException("Failed to flush recording temp log for " + name, e);
        }
    }

    private void closeAppendLog() {
        try {
            appendLogWriter.close();
        } catch (IOException e) {
            resolveLogger().log(Level.SEVERE, "Failed to close recording temp log: " + name, e);
        }
    }

    private void deleteAppendLog() {
        if (appendLogFile.exists()) {
            appendLogFile.delete();
        }
    }

    private void captureChunkBaselines() {
        try {
            chunkCaptureCoordinator.captureTrackedChunks(trackedChunkCollector.collect(tracker));
        } catch (IOException | RuntimeException e) {
            chunkCaptureFailed = true;
            resolveLogger().log(Level.SEVERE, "Failed to capture chunk baselines for recording: " + name, e);
            closeChunkCapture();
        }
    }

    private ChunkBaselineCaptureService createChunkBaselineCaptureService() {
        ChunkBaselineCaptureService captureService = new WorldChunkPacketFriendlyCaptureService(
                new BinaryPacketFriendlyChunkPayloadCodec());
        if (replay.getFoliaLib() != null && replay.getFoliaLib().isFolia()) {
            return new FoliaRegionChunkBaselineCaptureService(replay.getFoliaLib(), captureService);
        }
        return captureService;
    }

    private TrackedChunkCollector createTrackedChunkCollector() {
        if (replay.getFoliaLib() != null && replay.getFoliaLib().isFolia()) {
            FoliaTrackedChunkCollector collector = new FoliaTrackedChunkCollector(replay.getFoliaLib());
            return collector::collectTrackedPlayerChunks;
        }
        return EntityTracker::collectTrackedPlayerChunks;
    }

    @FunctionalInterface
    private interface TrackedChunkCollector {
        Set<ChunkCoordinate> collect(EntityTracker tracker) throws IOException;
    }

    private ChunkRecordingArtifacts closeChunkCapture() {
        if (chunkCaptureCoordinator == null) {
            return ChunkRecordingArtifacts.NONE;
        }
        try {
            chunkCaptureCoordinator.close();
            return chunkCaptureCoordinator.snapshotArtifacts();
        } catch (IOException e) {
            resolveLogger().log(Level.SEVERE, "Failed to close chunk capture temp files: " + name, e);
            return ChunkRecordingArtifacts.NONE;
        }
    }

    private void deleteChunkCaptureDirectory() {
        if (!chunkCaptureDirectory.exists()) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(chunkCaptureDirectory.toPath())) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            java.nio.file.Files.deleteIfExists(path);
                        } catch (IOException e) {
                            resolveLogger().log(Level.WARNING, "Failed to delete chunk capture temp path: " + path, e);
                        }
                    });
        } catch (IOException e) {
            resolveLogger().log(Level.WARNING, "Failed to enumerate chunk capture temp directory: " + chunkCaptureDirectory, e);
        }
    }

    private Logger resolveLogger() {
        Logger logger = replay.getLogger();
        return logger != null ? logger : Logger.getLogger(RecordingSession.class.getName());
    }
}
