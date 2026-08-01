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
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.SplashPotion;
import org.bukkit.inventory.meta.PotionMeta;

import java.util.UUID;
import java.util.function.Consumer;

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
    private final Consumer<UUID> storageDirtyMarker;
    private final Consumer<UUID> equipmentDirtyMarker;

    @FunctionalInterface
    public interface TickProvider {
        int getTick();
    }

    public RecordingEventHandler(
            EntityTracker tracker,
            TimelineBuilder builder,
            TickProvider tickProvider,
            Consumer<UUID> storageDirtyMarker,
            Consumer<UUID> equipmentDirtyMarker
    ) {
        this.tracker = tracker;
        this.builder = builder;
        this.tickProvider = tickProvider;
        this.storageDirtyMarker = storageDirtyMarker;
        this.equipmentDirtyMarker = equipmentDirtyMarker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        if (!tracker.isTrackedPlayer(e.getPlayer().getUniqueId())) return;

        markStorageDirty(e.getPlayer().getUniqueId());
        markEquipmentDirty(e.getPlayer().getUniqueId());

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

        markStorageDirty(p.getUniqueId());
        markEquipmentDirty(p.getUniqueId());

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

        markStorageDirty(e.getPlayer().getUniqueId());
        markEquipmentDirty(e.getPlayer().getUniqueId());

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

        markStorageDirty(p.getUniqueId());
        markEquipmentDirty(p.getUniqueId());

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
    public void onItemSwapHands(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!tracker.isTrackedPlayer(p.getUniqueId())) return;

        markStorageDirty(p.getUniqueId());
        markEquipmentDirty(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        if (!tracker.isTrackedPlayer(p.getUniqueId())) return;

        markEquipmentDirty(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent e) {
        if (!tracker.isTrackedPlayer(e.getPlayer().getUniqueId())) return;
        markStorageDirty(e.getPlayer().getUniqueId());
        markEquipmentDirty(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!tracker.isTrackedPlayer(player.getUniqueId())) return;
        markStorageDirty(player.getUniqueId());
        markEquipmentDirty(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!tracker.isTrackedPlayer(player.getUniqueId())) return;
        markStorageDirty(player.getUniqueId());
        markEquipmentDirty(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickupItem(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player player)) return;
        if (!tracker.isTrackedPlayer(player.getUniqueId())) return;
        markStorageDirty(player.getUniqueId());
        markEquipmentDirty(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemBreak(PlayerItemBreakEvent e) {
        if (!tracker.isTrackedPlayer(e.getPlayer().getUniqueId())) return;
        markStorageDirty(e.getPlayer().getUniqueId());
        markEquipmentDirty(e.getPlayer().getUniqueId());
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
                e.getEntity().getLocation().getZ(),
                e.getEntity() instanceof SplashPotion potion ? serializeItem(potion.getItem()) : null
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
    public void onProjectileHit(ProjectileHitEvent e) {
        if (e.getEntity() instanceof EnderPearl pearl) {
            UUID uuid = pearl.getUniqueId();
            if (!tracker.isEntityTracked(uuid)) return;

            Location location = pearl.getLocation();
            builder.addEvent(new TimelineEvent.EntityDeath(
                    tickProvider.getTick(),
                    uuid.toString(),
                    pearl.getType().name(),
                    location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ()
            ));
            tracker.removeEntity(uuid);
            return;
        }

        if (!(e.getEntity() instanceof SplashPotion potion)) return;

        UUID uuid = potion.getUniqueId();
        if (!tracker.isEntityTracked(uuid)) return;

        Location location = potion.getLocation();
        org.bukkit.Color color = potion.getItem().getItemMeta() instanceof PotionMeta meta
                && meta.getColor() != null ? meta.getColor() : org.bukkit.Color.fromRGB(56, 90, 255);
        builder.addEvent(new TimelineEvent.SplashPotionImpact(
                tickProvider.getTick(), uuid.toString(), location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(), color.asRGB()
        ));
        builder.addEvent(new TimelineEvent.EntityDeath(
                tickProvider.getTick(),
                uuid.toString(),
                potion.getType().name(),
                location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ()
        ));
        tracker.removeEntity(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnderPearlTeleport(PlayerTeleportEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || !tracker.isTrackedPlayer(e.getPlayer().getUniqueId())) return;

        Location location = e.getTo();
        if (location == null || location.getWorld() == null) return;

        builder.addEvent(new TimelineEvent.SoundEffect(
                tickProvider.getTick(),
                e.getPlayer().getUniqueId().toString(),
                "minecraft:entity.player.teleport", location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(), 1.0f, 1.0f
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();

        Player p = e.getPlayer();

        if (!tracker.isTrackedPlayer(uuid)) return;

        markStorageDirty(uuid);
        markEquipmentDirty(uuid);

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

    private void markStorageDirty(UUID uuid) {
        storageDirtyMarker.accept(uuid);
    }

    private void markEquipmentDirty(UUID uuid) {
        equipmentDirtyMarker.accept(uuid);
    }
}
