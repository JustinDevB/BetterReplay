package me.justindevb.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import me.justindevb.replay.util.ReplayObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import java.io.File;
import java.util.*;

import static me.justindevb.replay.util.ItemStackSerializer.serializeItem;

public class RecordingSession implements Listener, PacketListener {

    private final Replay replay;
    private final String name;
    private final File file;
    private final Set<UUID> trackedPlayers;
    private final Map<UUID, EntityType> trackedEntities = new HashMap<>();
    private final List<Map<String, Object>> timeline = new ArrayList<>();
    private PacketListenerCommon packetListenerHandle;
    private final Map<String, Integer> breakStageDedup = new HashMap<>();

    private static final double NEARBY_RADIUS_SQUARED = 32.0 * 32.0;
    private static final int INVENTORY_CHECK_INTERVAL = 5;
    private final Map<UUID, List<String>> lastInventorySnapshot = new HashMap<>();
    private int tick = 0;
    private int durationTicks = -1;
    private boolean stopped = false;


    public RecordingSession(String name, File folder, Collection<Player> players, int durationSeconds) {
        this.name = name;
        this.file = new File(folder, "replays/" + name + ".json");
        this.trackedPlayers = new HashSet<>();
        for (Player p : players) this.trackedPlayers.add(p.getUniqueId());
        this.durationTicks = durationSeconds > 0 ? durationSeconds * 20 : -1;
        this.replay = Replay.getInstance();
    }

    public void start() {
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();

        Bukkit.getLogger().info("Started recording: " + name + " for " + trackedPlayers.size()
                + " player(s), duration=" + (durationTicks == -1 ? "∞" : durationTicks / 20 + "s"));

        Bukkit.getPluginManager().registerEvents(this, replay);
        packetListenerHandle = PacketEvents.getAPI().getEventManager().registerListener(this, PacketListenerPriority.NORMAL);

        captureInitialInventory();
    }

    /** Called every tick by RecorderManager */
    public void tick() {
        if (stopped) return;

        if (durationTicks != -1 && tick >= durationTicks) {
            stop(true);
            return;
        }

        for (UUID uuid : trackedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;

            Location loc = p.getLocation();
            Map<String, Object> moveEvent = new HashMap<>();
            moveEvent.put("tick", tick);
            moveEvent.put("type", "player_move");
            moveEvent.put("name", p.getName());
            moveEvent.put("etype", EntityType.PLAYER.name());
            moveEvent.put("uuid", uuid.toString());
            moveEvent.put("world", p.getWorld().getName());
            moveEvent.put("x", loc.getX());
            moveEvent.put("y", loc.getY());
            moveEvent.put("z", loc.getZ());
            moveEvent.put("yaw", loc.getYaw());
            moveEvent.put("pitch", loc.getPitch());

            moveEvent.put("pose", p.getPose().name());

            timeline.add(moveEvent);
        }

        for (Map.Entry<UUID, EntityType> entry : trackedEntities.entrySet()) {
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
            timeline.add(moveEvent);
        }

        if (tick % INVENTORY_CHECK_INTERVAL == 0) {
            tickInventoryCheck();
        }

        tick++;
    }

    private void tickInventoryCheck() {
        for (UUID uuid : trackedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;

            List<String> currentSerialized = new ArrayList<>();
            // Include held slot so switching selected hotbar slot triggers a diff
            currentSerialized.add(String.valueOf(p.getInventory().getHeldItemSlot()));
            for (ItemStack item : p.getInventory().getContents()) {
                currentSerialized.add(serializeItem(item));
            }
            // Include armor and offhand in the comparison key
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
            event.putAll(captureInventory(p));
            timeline.add(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        if (!trackedPlayers.contains(e.getPlayer().getUniqueId())) return;

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tick);
        event.put("type", "block_break");
        event.put("uuid", e.getPlayer().getUniqueId().toString());
        event.put("world", e.getBlock().getWorld().getName());
        event.put("x", e.getBlock().getX());
        event.put("y", e.getBlock().getY());
        event.put("z", e.getBlock().getZ());
        event.put("blockData", e.getBlock().getBlockData().getAsString());
        timeline.add(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent e) {
        if (!trackedPlayers.contains(e.getPlayer().getUniqueId())) return;

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tick);
        event.put("type", "block_break_complete");
        event.put("uuid", e.getPlayer().getUniqueId().toString());
        event.put("world", e.getBlock().getWorld().getName());
        event.put("x", e.getBlock().getX());
        event.put("y", e.getBlock().getY());
        event.put("z", e.getBlock().getZ());
        timeline.add(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDrop(PlayerDropItemEvent e) {
        Player p =  e.getPlayer();
        if (!trackedPlayers.contains(p.getUniqueId()))
            return;

        ItemStack dropped = e.getItemDrop().getItemStack();

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tick);
        event.put("type", "item_drop");
        event.put("uuid", p.getUniqueId().toString());
        event.put("item", serializeItem(dropped));
        event.put("loc", serializeLocation(p.getLocation()));
        timeline.add(event);

    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (!trackedPlayers.contains(e.getPlayer().getUniqueId())) return;

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tick);
        event.put("type", "block_place");
        event.put("uuid", e.getPlayer().getUniqueId().toString());
        event.put("world", e.getBlock().getWorld().getName());
        event.put("x", e.getBlock().getX());
        event.put("y", e.getBlock().getY());
        event.put("z", e.getBlock().getZ());
        event.put("blockData", e.getBlock().getBlockData().getAsString());
        event.put("replacedBlockData", e.getBlockReplacedState().getBlockData().getAsString());
        timeline.add(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!trackedPlayers.contains(p.getUniqueId())) return;

        Entity entity = e.getEntity();

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tick);
        event.put("type", "attack");
        event.put("uuid", p.getUniqueId().toString());
        if (e.getEntity() instanceof Player target) {
            event.put("targetUuid", target.getUniqueId().toString());
        }

        event.put("entityUuid", entity.getUniqueId().toString());
        event.put("entityType", entity.getType().name());
        timeline.add(event);

        if (!(entity instanceof Player) && !trackedEntities.containsKey(entity.getUniqueId())) {
            trackedEntities.put(entity.getUniqueId(), entity.getType());

            Map<String, Object> spawnEvent = new HashMap<>();
            spawnEvent.put("tick", tick);
            spawnEvent.put("type", "entity_spawn");
            spawnEvent.put("uuid", entity.getUniqueId().toString());
            spawnEvent.put("etype", entity.getType().name());
            spawnEvent.put("world", entity.getWorld().getName());
            spawnEvent.put("x", entity.getLocation().getX());
            spawnEvent.put("y", entity.getLocation().getY());
            spawnEvent.put("z", entity.getLocation().getZ());
            timeline.add(spawnEvent);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerAnimation(PlayerAnimationEvent e) {
        if (!trackedPlayers.contains(e.getPlayer().getUniqueId())) return;

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tick);
        event.put("type", "swing");
        event.put("uuid", e.getPlayer().getUniqueId().toString());
        event.put("hand", e.getAnimationType().name());
        timeline.add(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSprintToggle(PlayerToggleSprintEvent e) {
        if (!trackedPlayers.contains(e.getPlayer().getUniqueId())) return;

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tick);
        event.put("type", e.isSprinting() ? "sprint_start" : "sprint_stop");
        event.put("uuid", e.getPlayer().getUniqueId().toString());
        timeline.add(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!trackedPlayers.contains(e.getPlayer().getUniqueId())) return;

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tick);
        event.put("type", e.isSneaking() ? "sneak_start" : "sneak_stop");
        event.put("uuid", e.getPlayer().getUniqueId().toString());
        timeline.add(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamaged(EntityDamageEvent e) {
        if (!isTrackedPlayer(e.getEntity().getUniqueId())) return;
        Map<String, Object> event = new HashMap<>();
        event.put("tick", tick);
        event.put("type", "damaged");
        event.put("uuid", e.getEntity().getUniqueId().toString());
        event.put("entityType", e.getEntity().getType().name());
        event.put("cause", e.getCause().name());
        event.put("finalDamage", e.getFinalDamage());
        timeline.add(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitySpawn(org.bukkit.event.entity.EntitySpawnEvent e) {
        if (!isNearbyTrackedPlayer(e.getEntity().getLocation())) return;

        UUID uuid = e.getEntity().getUniqueId();
        if (trackedEntities.containsKey(uuid)) return;

        trackedEntities.put(uuid, e.getEntityType());

        Map<String, Object> spawnEvent = new HashMap<>();
        spawnEvent.put("tick", tick);
        spawnEvent.put("type", "entity_spawn");
        spawnEvent.put("uuid", uuid.toString());
        spawnEvent.put("etype", e.getEntityType().name());
        spawnEvent.put("world", e.getLocation().getWorld().getName());
        spawnEvent.put("x", e.getEntity().getLocation().getX());
        spawnEvent.put("y", e.getEntity().getLocation().getY());
        spawnEvent.put("z", e.getEntity().getLocation().getZ());
        timeline.add(spawnEvent);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent e) {
        Entity entity = e.getEntity();

        UUID uuid = entity.getUniqueId();
        if (!trackedEntities.containsKey(uuid)) return;

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tick);
        event.put("type", "entity_death");
        event.put("uuid", uuid.toString());
        event.put("etype", e.getEntityType().name());
        event.put("world", entity.getLocation().getWorld().getName());
        event.put("x", entity.getLocation().getX());
        event.put("y", entity.getLocation().getY());
        event.put("z", entity.getLocation().getZ());

        timeline.add(event);

        if (!(e instanceof Player))
            trackedEntities.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();

        Player p = e.getPlayer();

        if (!trackedPlayers.contains(uuid)) return;

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tick);
        event.put("type", "entity_death");
        event.put("uuid", uuid.toString());
        event.put("etype", e.getEntityType().name());
        event.put("world", p.getWorld().getName());
        event.put("x", p.getLocation().getX());
        event.put("y", p.getLocation().getY());
        event.put("z", p.getLocation().getZ());

        timeline.add(event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();

        if (trackedPlayers.contains(p.getUniqueId())) {
            Map<String, Object> event = new HashMap<>();
            event.put("tick", tick);
            event.put("type", "player_quit");
            event.put("uuid", p.getUniqueId().toString());

            timeline.add(event);
            trackedPlayers.remove(p.getUniqueId());
        }
    }

    public void stop(boolean save) {
        if (stopped) return;
        stopped = true;
        HandlerList.unregisterAll(this);
        if (packetListenerHandle != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListenerHandle);
            packetListenerHandle = null;
        }

        trackedPlayers.clear();

        if (!save) return;

        ReplayObject replayObject = new ReplayObject(
                name,
                timeline,
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

    public List<Map<String, Object>> getTimeline() {
        return timeline;
    }

    public Set<UUID> getTrackedPlayers() {
        return trackedPlayers;
    }

    public boolean isTrackedPlayer(UUID uuid) {
        return getTrackedPlayers().contains(uuid);
    }

    private void captureInitialInventory() {
        for (UUID uuid : trackedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;

            Map<String, Object> inventoryEvent = new HashMap<>();
            inventoryEvent.put("tick", tick);
            inventoryEvent.put("type", "inventory_update");
            inventoryEvent.put("uuid", uuid.toString());
            inventoryEvent.putAll(captureInventory(p));

            timeline.add(inventoryEvent);
        }

    }

    @Override
    public void onPacketSend(PacketSendEvent e) {
        if (e.getPacketType() == PacketType.Play.Server.BLOCK_BREAK_ANIMATION) {
            WrapperPlayServerBlockBreakAnimation packet = new WrapperPlayServerBlockBreakAnimation(e);
            Player p = e.getPlayer();

            String world = p.getWorld().getName();

            int stage = packet.getDestroyStage();
            int x = packet.getBlockPosition().getX();
            int y = packet.getBlockPosition().getY();
            int z = packet.getBlockPosition().getZ();

            String dedupKey = world + ":" + x + ":" + y + ":" + z + ":" + stage;
            Integer lastTick = breakStageDedup.get(dedupKey);
            if (lastTick != null && lastTick == tick) {
                return;
            }
            breakStageDedup.put(dedupKey, tick);
            if (breakStageDedup.size() > 4000) {
                breakStageDedup.entrySet().removeIf(entry -> entry.getValue() < tick - 40);
            }

            String breakerUuid = null;
            Entity entity = SpigotConversionUtil.getEntityById(p.getWorld(), packet.getEntityId());
            if (entity instanceof Player breaker && isTrackedPlayer(breaker.getUniqueId())) {
                breakerUuid = breaker.getUniqueId().toString();
            }

            if (breakerUuid == null && !isTrackedPlayer(p.getUniqueId())) {
                return;
            }

            Map<String, Object> event = new HashMap<>();
            event.put("tick", tick);
            event.put("type", "block_break_stage");
            if (breakerUuid != null) {
                event.put("uuid", breakerUuid);
            }
            event.put("world", world);
            event.put("x", x);
            event.put("y", y);
            event.put("z", z);
            event.put("stage", stage);
            timeline.add(event);
        }
    }

    private Map<String, Object> captureInventory(Player p) {
        Map<String, Object> invSnapshot = new HashMap<>();

        invSnapshot.put("mainHand", serializeItem(p.getInventory().getItemInMainHand()));
        invSnapshot.put("offHand", serializeItem(p.getInventory().getItemInOffHand()));

        List<String> armor = new ArrayList<>(4);
        armor.add(serializeItem(p.getInventory().getBoots()));
        armor.add(serializeItem(p.getInventory().getLeggings()));
        armor.add(serializeItem(p.getInventory().getChestplate()));
        armor.add(serializeItem(p.getInventory().getHelmet()));
        invSnapshot.put("armor", armor);

        List<String> contents = new ArrayList<>();
        for (ItemStack item : p.getInventory().getContents()) {
            contents.add(serializeItem(item));
        }
        invSnapshot.put("contents", contents);

        return invSnapshot;
    }

    private Map<String, Object> serializeLocation(org.bukkit.Location loc) {
        Map<String, Object> map = new HashMap<>();
        map.put("x", loc.getX());
        map.put("y", loc.getY());
        map.put("z", loc.getZ());
        map.put("yaw", loc.getYaw());
        map.put("pitch", loc.getPitch());
        map.put("world", loc.getWorld().getName());
        return map;
    }

    private boolean isNearbyTrackedPlayer(Location spawnLoc) {
        if (spawnLoc == null || spawnLoc.getWorld() == null) {
            return false;
        }

        World spawnWorld = spawnLoc.getWorld();

        for (UUID uuid : Set.copyOf(trackedPlayers)) {
            Player tracked = Bukkit.getPlayer(uuid);
            if (tracked == null) continue;

            if (tracked.getWorld() != spawnWorld) continue;

            if (tracked.getLocation().distanceSquared(spawnLoc) <= NEARBY_RADIUS_SQUARED) {
                return true;
            }
        }

        return false;
    }


}
