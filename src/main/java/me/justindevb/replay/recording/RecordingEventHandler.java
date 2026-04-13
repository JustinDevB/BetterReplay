package me.justindevb.replay.recording;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static me.justindevb.replay.util.io.ItemStackSerializer.serializeItem;

/**
 * Handles Bukkit events during a recording session.
 * Captures block breaks/places, deaths, attacks, animations, and entity spawns
 * into the timeline via the TimelineBuilder.
 */
public class RecordingEventHandler implements Listener {

    private final EntityTracker tracker;
    private final TimelineBuilder builder;
    private final TickProvider tickProvider;

    @FunctionalInterface
    public interface TickProvider {
        int getTick();
    }

    public RecordingEventHandler(EntityTracker tracker, TimelineBuilder builder, TickProvider tickProvider) {
        this.tracker = tracker;
        this.builder = builder;
        this.tickProvider = tickProvider;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        if (!tracker.isTrackedPlayer(e.getPlayer().getUniqueId())) return;

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tickProvider.getTick());
        event.put("type", "block_break");
        event.put("uuid", e.getPlayer().getUniqueId().toString());
        event.put("world", e.getBlock().getWorld().getName());
        event.put("x", e.getBlock().getX());
        event.put("y", e.getBlock().getY());
        event.put("z", e.getBlock().getZ());
        event.put("blockData", e.getBlock().getBlockData().getAsString());
        builder.addEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent e) {
        if (!tracker.isTrackedPlayer(e.getPlayer().getUniqueId())) return;

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tickProvider.getTick());
        event.put("type", "block_break_complete");
        event.put("uuid", e.getPlayer().getUniqueId().toString());
        event.put("world", e.getBlock().getWorld().getName());
        event.put("x", e.getBlock().getX());
        event.put("y", e.getBlock().getY());
        event.put("z", e.getBlock().getZ());
        builder.addEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (!tracker.isTrackedPlayer(p.getUniqueId())) return;

        ItemStack dropped = e.getItemDrop().getItemStack();

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tickProvider.getTick());
        event.put("type", "item_drop");
        event.put("uuid", p.getUniqueId().toString());
        event.put("item", serializeItem(dropped));
        event.put("loc", builder.serializeLocation(p.getLocation()));
        builder.addEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (!tracker.isTrackedPlayer(e.getPlayer().getUniqueId())) return;

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tickProvider.getTick());
        event.put("type", "block_place");
        event.put("uuid", e.getPlayer().getUniqueId().toString());
        event.put("world", e.getBlock().getWorld().getName());
        event.put("x", e.getBlock().getX());
        event.put("y", e.getBlock().getY());
        event.put("z", e.getBlock().getZ());
        event.put("blockData", e.getBlock().getBlockData().getAsString());
        event.put("replacedBlockData", e.getBlockReplacedState().getBlockData().getAsString());
        builder.addEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!tracker.isTrackedPlayer(p.getUniqueId())) return;

        Entity entity = e.getEntity();

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tickProvider.getTick());
        event.put("type", "attack");
        event.put("uuid", p.getUniqueId().toString());
        if (e.getEntity() instanceof Player target) {
            event.put("targetUuid", target.getUniqueId().toString());
        }

        event.put("entityUuid", entity.getUniqueId().toString());
        event.put("entityType", entity.getType().name());
        builder.addEvent(event);

        if (!(entity instanceof Player) && !tracker.isEntityTracked(entity.getUniqueId())) {
            tracker.trackEntity(entity.getUniqueId(), entity.getType());

            Map<String, Object> spawnEvent = new HashMap<>();
            spawnEvent.put("tick", tickProvider.getTick());
            spawnEvent.put("type", "entity_spawn");
            spawnEvent.put("uuid", entity.getUniqueId().toString());
            spawnEvent.put("etype", entity.getType().name());
            spawnEvent.put("world", entity.getWorld().getName());
            spawnEvent.put("x", entity.getLocation().getX());
            spawnEvent.put("y", entity.getLocation().getY());
            spawnEvent.put("z", entity.getLocation().getZ());
            builder.addEvent(spawnEvent);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerAnimation(PlayerAnimationEvent e) {
        if (!tracker.isTrackedPlayer(e.getPlayer().getUniqueId())) return;

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tickProvider.getTick());
        event.put("type", "swing");
        event.put("uuid", e.getPlayer().getUniqueId().toString());
        event.put("hand", e.getAnimationType().name());
        builder.addEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSprintToggle(PlayerToggleSprintEvent e) {
        if (!tracker.isTrackedPlayer(e.getPlayer().getUniqueId())) return;

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tickProvider.getTick());
        event.put("type", e.isSprinting() ? "sprint_start" : "sprint_stop");
        event.put("uuid", e.getPlayer().getUniqueId().toString());
        builder.addEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!tracker.isTrackedPlayer(e.getPlayer().getUniqueId())) return;

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tickProvider.getTick());
        event.put("type", e.isSneaking() ? "sneak_start" : "sneak_stop");
        event.put("uuid", e.getPlayer().getUniqueId().toString());
        builder.addEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamaged(EntityDamageEvent e) {
        if (!tracker.isTrackedPlayer(e.getEntity().getUniqueId())) return;
        Map<String, Object> event = new HashMap<>();
        event.put("tick", tickProvider.getTick());
        event.put("type", "damaged");
        event.put("uuid", e.getEntity().getUniqueId().toString());
        event.put("entityType", e.getEntity().getType().name());
        event.put("cause", e.getCause().name());
        event.put("finalDamage", e.getFinalDamage());
        builder.addEvent(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitySpawn(org.bukkit.event.entity.EntitySpawnEvent e) {
        if (!tracker.isNearbyTrackedPlayer(e.getEntity().getLocation())) return;

        UUID uuid = e.getEntity().getUniqueId();
        if (tracker.isEntityTracked(uuid)) return;

        tracker.trackEntity(uuid, e.getEntityType());

        Map<String, Object> spawnEvent = new HashMap<>();
        spawnEvent.put("tick", tickProvider.getTick());
        spawnEvent.put("type", "entity_spawn");
        spawnEvent.put("uuid", uuid.toString());
        spawnEvent.put("etype", e.getEntityType().name());
        spawnEvent.put("world", e.getLocation().getWorld().getName());
        spawnEvent.put("x", e.getEntity().getLocation().getX());
        spawnEvent.put("y", e.getEntity().getLocation().getY());
        spawnEvent.put("z", e.getEntity().getLocation().getZ());
        builder.addEvent(spawnEvent);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent e) {
        Entity entity = e.getEntity();

        UUID uuid = entity.getUniqueId();
        if (!tracker.isEntityTracked(uuid)) return;

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tickProvider.getTick());
        event.put("type", "entity_death");
        event.put("uuid", uuid.toString());
        event.put("etype", e.getEntityType().name());
        event.put("world", entity.getLocation().getWorld().getName());
        event.put("x", entity.getLocation().getX());
        event.put("y", entity.getLocation().getY());
        event.put("z", entity.getLocation().getZ());

        builder.addEvent(event);

        if (!(entity instanceof Player))
            tracker.removeEntity(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();

        Player p = e.getPlayer();

        if (!tracker.isTrackedPlayer(uuid)) return;

        Map<String, Object> event = new HashMap<>();
        event.put("tick", tickProvider.getTick());
        event.put("type", "entity_death");
        event.put("uuid", uuid.toString());
        event.put("etype", e.getEntityType().name());
        event.put("world", p.getWorld().getName());
        event.put("x", p.getLocation().getX());
        event.put("y", p.getLocation().getY());
        event.put("z", p.getLocation().getZ());

        builder.addEvent(event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();

        if (tracker.isTrackedPlayer(p.getUniqueId())) {
            Map<String, Object> event = new HashMap<>();
            event.put("tick", tickProvider.getTick());
            event.put("type", "player_quit");
            event.put("uuid", p.getUniqueId().toString());

            builder.addEvent(event);
            tracker.removePlayer(p.getUniqueId());
        }
    }
}
