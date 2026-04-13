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
import org.bukkit.Location;

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

        builder.addEvent(new TimelineEvent.BlockBreak(
                tickProvider.getTick(),
                e.getPlayer().getUniqueId().toString(),
                e.getBlock().getWorld().getName(),
                e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ(),
                e.getBlock().getBlockData().getAsString()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent e) {
        if (!tracker.isTrackedPlayer(e.getPlayer().getUniqueId())) return;

        builder.addEvent(new TimelineEvent.BlockBreakComplete(
                tickProvider.getTick(),
                e.getPlayer().getUniqueId().toString(),
                e.getBlock().getWorld().getName(),
                e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (!tracker.isTrackedPlayer(p.getUniqueId())) return;

        ItemStack dropped = e.getItemDrop().getItemStack();
        Location loc = p.getLocation();

        builder.addEvent(new TimelineEvent.ItemDrop(
                tickProvider.getTick(),
                p.getUniqueId().toString(),
                serializeItem(dropped),
                loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (!tracker.isTrackedPlayer(e.getPlayer().getUniqueId())) return;

        builder.addEvent(new TimelineEvent.BlockPlace(
                tickProvider.getTick(),
                e.getPlayer().getUniqueId().toString(),
                e.getBlock().getWorld().getName(),
                e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ(),
                e.getBlock().getBlockData().getAsString(),
                e.getBlockReplacedState().getBlockData().getAsString()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!tracker.isTrackedPlayer(p.getUniqueId())) return;

        Entity entity = e.getEntity();

        String targetUuid = (entity instanceof Player target) ? target.getUniqueId().toString() : null;

        builder.addEvent(new TimelineEvent.Attack(
                tickProvider.getTick(),
                p.getUniqueId().toString(),
                targetUuid,
                entity.getUniqueId().toString(),
                entity.getType().name()
        ));

        if (!(entity instanceof Player) && !tracker.isEntityTracked(entity.getUniqueId())) {
            tracker.trackEntity(entity.getUniqueId(), entity.getType());

            builder.addEvent(new TimelineEvent.EntitySpawn(
                    tickProvider.getTick(),
                    entity.getUniqueId().toString(),
                    entity.getType().name(),
                    entity.getWorld().getName(),
                    entity.getLocation().getX(),
                    entity.getLocation().getY(),
                    entity.getLocation().getZ()
            ));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerAnimation(PlayerAnimationEvent e) {
        if (!tracker.isTrackedPlayer(e.getPlayer().getUniqueId())) return;

        builder.addEvent(new TimelineEvent.Swing(
                tickProvider.getTick(),
                e.getPlayer().getUniqueId().toString(),
                e.getAnimationType().name()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSprintToggle(PlayerToggleSprintEvent e) {
        if (!tracker.isTrackedPlayer(e.getPlayer().getUniqueId())) return;

        builder.addEvent(new TimelineEvent.SprintToggle(
                tickProvider.getTick(),
                e.getPlayer().getUniqueId().toString(),
                e.isSprinting()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!tracker.isTrackedPlayer(e.getPlayer().getUniqueId())) return;

        builder.addEvent(new TimelineEvent.SneakToggle(
                tickProvider.getTick(),
                e.getPlayer().getUniqueId().toString(),
                e.isSneaking()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamaged(EntityDamageEvent e) {
        if (!tracker.isTrackedPlayer(e.getEntity().getUniqueId())) return;

        builder.addEvent(new TimelineEvent.Damaged(
                tickProvider.getTick(),
                e.getEntity().getUniqueId().toString(),
                e.getEntity().getType().name(),
                e.getCause().name(),
                e.getFinalDamage()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitySpawn(org.bukkit.event.entity.EntitySpawnEvent e) {
        if (!tracker.isNearbyTrackedPlayer(e.getEntity().getLocation())) return;

        UUID uuid = e.getEntity().getUniqueId();
        if (tracker.isEntityTracked(uuid)) return;

        tracker.trackEntity(uuid, e.getEntityType());

        builder.addEvent(new TimelineEvent.EntitySpawn(
                tickProvider.getTick(),
                uuid.toString(),
                e.getEntityType().name(),
                e.getLocation().getWorld().getName(),
                e.getEntity().getLocation().getX(),
                e.getEntity().getLocation().getY(),
                e.getEntity().getLocation().getZ()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent e) {
        Entity entity = e.getEntity();

        UUID uuid = entity.getUniqueId();
        if (!tracker.isEntityTracked(uuid)) return;

        builder.addEvent(new TimelineEvent.EntityDeath(
                tickProvider.getTick(),
                uuid.toString(),
                e.getEntityType().name(),
                entity.getLocation().getWorld().getName(),
                entity.getLocation().getX(),
                entity.getLocation().getY(),
                entity.getLocation().getZ()
        ));

        if (!(entity instanceof Player))
            tracker.removeEntity(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();

        Player p = e.getPlayer();

        if (!tracker.isTrackedPlayer(uuid)) return;

        builder.addEvent(new TimelineEvent.EntityDeath(
                tickProvider.getTick(),
                uuid.toString(),
                e.getEntityType().name(),
                p.getWorld().getName(),
                p.getLocation().getX(),
                p.getLocation().getY(),
                p.getLocation().getZ()
        ));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();

        if (tracker.isTrackedPlayer(p.getUniqueId())) {
            builder.addEvent(new TimelineEvent.PlayerQuit(
                    tickProvider.getTick(),
                    p.getUniqueId().toString()
            ));
            tracker.removePlayer(p.getUniqueId());
        }
    }
}
