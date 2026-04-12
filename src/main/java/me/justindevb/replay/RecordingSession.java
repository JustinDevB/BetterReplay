package me.justindevb.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import me.justindevb.replay.recording.EntityTracker;
import me.justindevb.replay.recording.RecordingEventHandler;
import me.justindevb.replay.recording.RecordingPacketHandler;
import me.justindevb.replay.recording.TimelineBuilder;
import me.justindevb.replay.util.ReplayObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;

import static me.justindevb.replay.util.io.ItemStackSerializer.serializeItem;

/**
 * Coordinates a recording session. Owns the tick loop and delegates event handling,
 * entity tracking, and timeline building to focused components in the recording package.
 */
public class RecordingSession {

    private final Replay replay;
    private final String name;
    private final File file;

    private final EntityTracker tracker;
    private final TimelineBuilder builder;
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
        this.file = new File(folder, "replays/" + name + ".json");
        this.durationTicks = durationSeconds > 0 ? durationSeconds * 20 : -1;
        this.replay = Replay.getInstance();

        this.tracker = new EntityTracker(players);
        this.builder = new TimelineBuilder();
        this.eventHandler = new RecordingEventHandler(tracker, builder, this::getTick);
        this.packetHandler = new RecordingPacketHandler(tracker, builder, this::getTick);
    }

    public void start() {
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();

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
            Map<String, Object> moveEvent = new HashMap<>();
            moveEvent.put("tick", tick);
            moveEvent.put("type", "player_move");
            moveEvent.put("name", p.getName());
            moveEvent.put("etype", EntityType.PLAYER);
            moveEvent.put("uuid", uuid.toString());
            moveEvent.put("world", p.getWorld().getName());
            moveEvent.put("x", loc.getX());
            moveEvent.put("y", loc.getY());
            moveEvent.put("z", loc.getZ());
            moveEvent.put("yaw", loc.getYaw());
            moveEvent.put("pitch", loc.getPitch());

            moveEvent.put("pose", p.getPose().name());

            builder.addEvent(moveEvent);
        }

        for (Map.Entry<UUID, EntityType> entry : tracker.getTrackedEntities().entrySet()) {
            UUID uuid = entry.getKey();
            Entity e = Bukkit.getEntity(uuid);
            if (e == null || e.isDead()) continue;

            Map<String, Object> moveEvent = new HashMap<>();
            moveEvent.put("tick", tick);
            moveEvent.put("type", "entity_move");
            moveEvent.put("uuid", uuid.toString());
            moveEvent.put("etype", e.getType().name());
            moveEvent.put("world", e.getWorld().getName());
            moveEvent.put("x", e.getLocation().getX());
            moveEvent.put("y", e.getLocation().getY());
            moveEvent.put("z", e.getLocation().getZ());
            moveEvent.put("yaw", e.getLocation().getYaw());
            moveEvent.put("pitch", e.getLocation().getPitch());
            builder.addEvent(moveEvent);
        }

        if (tick % INVENTORY_CHECK_INTERVAL == 0) {
            tickInventoryCheck();
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

            Map<String, Object> event = new HashMap<>();
            event.put("tick", tick);
            event.put("type", "inventory_update");
            event.put("uuid", uuid.toString());
            event.putAll(builder.captureInventory(p));
            builder.addEvent(event);
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

        if (!save) return;

        ReplayObject replayObject = new ReplayObject(
                name,
                builder.getTimeline(),
                replay.getReplayStorage()
        );

        replayObject.save()
                .thenCompose(v ->
                        replay.getReplayStorage().listReplays()
                )
                .thenAccept(replays -> {
                    replay.getReplayCache().setReplays(replays);
                    replay.getLogger().info("Recording " + name + " saved!");
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    return null;
                });
    }

    public boolean isStopped() {
        return stopped;
    }

    public int getTick() {
        return tick;
    }

    public List<Map<String, Object>> getTimeline() {
        return builder.getTimeline();
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

            Map<String, Object> inventoryEvent = new HashMap<>();
            inventoryEvent.put("tick", tick);
            inventoryEvent.put("type", "inventory_update");
            inventoryEvent.put("uuid", uuid.toString());
            inventoryEvent.putAll(builder.captureInventory(p));

            builder.addEvent(inventoryEvent);
        }
    }
}
