package me.justindevb.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import me.justindevb.replay.recording.EntityTracker;
import me.justindevb.replay.recording.RecordingEventHandler;
import me.justindevb.replay.recording.RecordingPacketHandler;
import me.justindevb.replay.recording.TimelineBuilder;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.ReplaySaveRequest;
import me.justindevb.replay.storage.binary.BinaryReplayAppendLogHeader;
import me.justindevb.replay.storage.binary.BinaryReplayAppendLogRecovery;
import me.justindevb.replay.storage.binary.BinaryReplayAppendLogReader;
import me.justindevb.replay.storage.binary.BinaryReplayAppendLogWriter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static me.justindevb.replay.util.io.ItemStackSerializer.serializeItem;

/**
 * Coordinates a recording session. Owns the tick loop and delegates event handling,
 * entity tracking, and timeline building to focused components in the recording package.
 */
public class RecordingSession {

    private static final int APPEND_LOG_FLUSH_INTERVAL_TICKS = 20;

    private final Replay replay;
    private final String name;
    private final File appendLogFile;
    private final long recordingStartedAtEpochMillis;

    private final EntityTracker tracker;
    private final TimelineBuilder builder;
    private final BinaryReplayAppendLogWriter appendLogWriter;
    private final BinaryReplayAppendLogReader appendLogReader;
    private final RecordingEventHandler eventHandler;
    private final RecordingPacketHandler packetHandler;
    private PacketListenerCommon packetListenerHandle;

    private static final int INVENTORY_CHECK_INTERVAL = 5;
    private final Map<UUID, List<String>> lastInventorySnapshot = new HashMap<>();
    private int tick = 0;
    private int durationTicks = -1;
    private boolean stopped = false;

    public RecordingSession(String name, File folder, Collection<Player> players, int durationSeconds) {
        this.name = name;
        this.durationTicks = durationSeconds > 0 ? durationSeconds * 20 : -1;
        this.replay = Replay.getInstance();
        this.recordingStartedAtEpochMillis = System.currentTimeMillis();
        this.appendLogFile = new File(folder, "replays/.tmp/" + name + ".appendlog");
        this.appendLogReader = new BinaryReplayAppendLogReader();

        try {
            this.appendLogWriter = new BinaryReplayAppendLogWriter(
                    appendLogFile.toPath(),
                    new BinaryReplayAppendLogHeader(recordingStartedAtEpochMillis));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create recording append-log for " + name, e);
        }

        this.tracker = new EntityTracker(players);
        this.builder = new TimelineBuilder(appendLogWriter, false);
        this.eventHandler = new RecordingEventHandler(tracker, builder, this::getTick);
        this.packetHandler = new RecordingPacketHandler(tracker, builder, this::getTick);
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

        if (tick % INVENTORY_CHECK_INTERVAL == 0) {
            tickInventoryCheck();
        }

        if ((tick + 1) % APPEND_LOG_FLUSH_INTERVAL_TICKS == 0) {
            flushAppendLog();
        }

        tick++;
    }

    private void tickInventoryCheck() {
        for (UUID uuid : tracker.getTrackedPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;

            List<String> currentSerialized = new ArrayList<>();
            currentSerialized.add(String.valueOf(p.getInventory().getHeldItemSlot()));
            for (ItemStack item : p.getInventory().getContents()) {
                currentSerialized.add(serializeItem(item));
            }
            currentSerialized.add(serializeItem(p.getInventory().getItemInOffHand()));
            for (ItemStack armor : p.getInventory().getArmorContents()) {
                currentSerialized.add(serializeItem(armor));
            }

            List<String> previous = lastInventorySnapshot.get(uuid);
            if (currentSerialized.equals(previous)) continue;

            lastInventorySnapshot.put(uuid, currentSerialized);

            builder.addEvent(builder.captureInventory(tick, uuid.toString(), p));
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

        if (!save) {
            deleteAppendLog();
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

        deleteAppendLog();

        long recoveredStart = recovery.header().recordingStartedAtEpochMillis() > 0
            ? recovery.header().recordingStartedAtEpochMillis()
            : recordingStartedAtEpochMillis;

        replay.getReplayStorage().saveReplay(name, new ReplaySaveRequest(recovery.timeline(), recoveredStart))
                .thenCompose(v ->
                        replay.getReplayStorage().listReplays()
                )
                .thenAccept(replays -> {
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

            builder.addEvent(builder.captureInventory(tick, uuid.toString(), p));
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
            replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to close recording temp log: " + name, e);
        }
    }

    private void deleteAppendLog() {
        if (appendLogFile.exists()) {
            appendLogFile.delete();
        }
    }
}
