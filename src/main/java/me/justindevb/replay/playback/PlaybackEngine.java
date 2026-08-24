package me.justindevb.replay.playback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import me.justindevb.replay.Replay;
import me.justindevb.replay.entity.RecordedEntity;
import me.justindevb.replay.entity.RecordedPlayer;
import me.justindevb.replay.recording.TimelineEvent;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;

import java.util.*;

import static me.justindevb.replay.util.io.ItemStackSerializer.deserializeItem;

/**
 * Dispatches replay timeline events to the appropriate RecordedEntity methods
 * and handles spawning of fake mobs/items.
 */
public class PlaybackEngine {

    private final Player viewer;
    private final Set<Integer> trackedEntityIds;
    private final Set<UUID> deadEntities;
    private final Map<UUID, RecordedEntity> recordedEntities;
    private final ReplayBlockManager blockManager;

    public PlaybackEngine(Player viewer, Replay replay,
                          Set<Integer> trackedEntityIds,
                          Set<UUID> deadEntities,
                          Map<UUID, RecordedEntity> recordedEntities,
                          ReplayBlockManager blockManager) {
        this.viewer = viewer;
        this.trackedEntityIds = trackedEntityIds;
        this.deadEntities = deadEntities;
        this.recordedEntities = recordedEntities;
        this.blockManager = blockManager;
    }

    public void handleEvent(RecordedEntity entity, TimelineEvent event) {
        switch (event) {
            case TimelineEvent.PlayerMove e -> {
                World world = Bukkit.getWorld(e.world());
                if (world == null) return;
                Location loc = new Location(world, e.x(), e.y(), e.z(), e.yaw(), e.pitch());
                entity.moveTo(loc);
                if (e.pose() != null && entity instanceof RecordedPlayer rp) {
                    try {
                        rp.setPose(Pose.valueOf(e.pose()));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
            case TimelineEvent.EntityMove e -> {
                World world = Bukkit.getWorld(e.world());
                if (world == null) return;
                Location loc = new Location(world, e.x(), e.y(), e.z(), e.yaw(), e.pitch());
                entity.moveTo(loc);
            }
            case TimelineEvent.SneakToggle e -> {
                if (entity instanceof RecordedPlayer rp) rp.updateSneak(e.sneaking());
            }
            case TimelineEvent.Attack e -> {
                if (entity instanceof RecordedPlayer rp) rp.playAttackAnimation();
            }
            case TimelineEvent.BlockPlace e -> {
                if (entity instanceof RecordedPlayer rp) rp.showBlockPlace();
                blockManager.applyReplayBlockChange(e, false);
            }
            case TimelineEvent.BlockBreak e -> {
                if (entity instanceof RecordedPlayer rp) rp.showBlockBreak(e.x(), e.y(), e.z(), 9);
                blockManager.applyReplayBlockChange(e, false);
            }
            case TimelineEvent.BlockBreakStage e -> {
                if (entity instanceof RecordedPlayer rp) rp.showBlockBreak(e.x(), e.y(), e.z(), e.stage());
            }
            case TimelineEvent.Swing e -> {
                if (entity instanceof RecordedPlayer rp) rp.playSwing(e.hand());
            }
            case TimelineEvent.Damaged e -> {
                entity.showDamage();
                updateHealth(entity, e.health());
                if (e.critical() && entity.getCurrentLocation() != null) {
                    Location location = entity.getCurrentLocation().clone().add(0, 0.9, 0);
                    viewer.spawnParticle(Particle.CRIT, location, 10, 0.25, 0.5, 0.25, 0.1);
                }
            }
            case TimelineEvent.HealthUpdate e -> updateHealth(entity, e.health());
            case TimelineEvent.SprintToggle e -> {
                if (entity instanceof RecordedPlayer rp) rp.updateSprint(e.sprinting());
            }
            case TimelineEvent.EntityDeath e -> {
                entity.showDeath();
                deadEntities.add(entity.getUuid());
                entity.destroy();
                recordedEntities.remove(entity.getUuid());
            }
            case TimelineEvent.InventoryStorageUpdate e -> {
                if (entity instanceof RecordedPlayer rp) rp.updateStorage(e);
            }
            case TimelineEvent.EquipmentStateUpdate e -> {
                if (entity instanceof RecordedPlayer rp) rp.updateEquipment(e);
            }
            case TimelineEvent.ItemDrop e -> {
                ItemStack stack = deserializeItem(e.item());
                Location loc = (e.locWorld() != null)
                        ? new Location(Bukkit.getWorld(e.locWorld()), e.locX(), e.locY(), e.locZ(), e.locYaw(), e.locPitch())
                        : null;
                if (stack != null && loc != null) spawnFakeDroppedItem(stack, loc);
            }
            case TimelineEvent.EntitySpawn e -> {
                trackedEntityIds.add(entity.getFakeEntityId());
                if ("ENDER_PEARL".equals(e.etype())) {
                    World world = Bukkit.getWorld(e.world());
                    if (world != null) {
                        viewer.playSound(new Location(world, e.x(), e.y(), e.z()),
                                Sound.ENTITY_ENDER_PEARL_THROW, 1.0f, 0.0f);
                    }
                } else if ("SPLASH_POTION".equals(e.etype())) {
                    setThrownItem(entity, e.item());
                    World world = Bukkit.getWorld(e.world());
                    if (world != null) {
                        viewer.playSound(new Location(world, e.x(), e.y(), e.z()),
                                Sound.ENTITY_SPLASH_POTION_THROW, 1.0f, 0.0f);
                    }
                }
            }
            case TimelineEvent.SoundEffect e -> playSound(e);
            case TimelineEvent.SplashPotionImpact e -> playSplashPotionImpact(e);
            case TimelineEvent.PlayerQuit e -> {
                UUID uuid = UUID.fromString(e.uuid());
                recordedEntities.remove(uuid);
                if (entity == null) return;
                entity.destroy();
                trackedEntityIds.remove(entity.getFakeEntityId());
            }
            default -> {} // BlockBreakComplete, etc. — no playback action needed
        }
    }

    public void playSound(TimelineEvent.SoundEffect event) {
        World world = Bukkit.getWorld(event.world());
        if (world == null || event.sound() == null) return;

        NamespacedKey soundKey = NamespacedKey.fromString(event.sound());
        if (soundKey == null) return;

        Sound sound = Registry.SOUNDS.get(soundKey);
        if (sound == null) return;

        viewer.playSound(new Location(world, event.x(), event.y(), event.z()),
                sound, event.volume(), event.pitch());
    }

    public void playSplashPotionImpact(TimelineEvent.SplashPotionImpact event) {
        World world = Bukkit.getWorld(event.world());
        if (world == null) return;

        Location location = new Location(world, event.x(), event.y(), event.z());
        viewer.playSound(location, Sound.ENTITY_SPLASH_POTION_BREAK, 1.0f, 1.0f);
        viewer.spawnParticle(Particle.EFFECT, location, 40, 0.35, 0.25, 0.35, 0,
                new Particle.Spell(Color.fromRGB(event.color()), 1.0f));
    }

    private void setThrownItem(RecordedEntity entity, String serializedItem) {
        ItemStack item = deserializeItem(serializedItem);
        if (item == null) return;

        com.github.retrooper.packetevents.protocol.item.ItemStack nmsItem =
                SpigotConversionUtil.fromBukkitItemStack(item);
        EntityData<com.github.retrooper.packetevents.protocol.item.ItemStack> itemData =
                new EntityData<>(8, EntityDataTypes.ITEMSTACK, nmsItem);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                new WrapperPlayServerEntityMetadata(entity.getFakeEntityId(), Collections.singletonList(itemData)));
    }

    public void updateHealth(RecordedEntity entity, double health) {
        if (health < 0) return;

        EntityData<Float> healthData = new EntityData<>(9, EntityDataTypes.FLOAT, (float) health);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                new WrapperPlayServerEntityMetadata(entity.getFakeEntityId(), Collections.singletonList(healthData)));
    }


    public void spawnFakeMob(RecordedEntity entity, TimelineEvent.EntitySpawn event) {
        Location loc = new Location(Bukkit.getWorld(event.world()),
                event.x(), event.y(), event.z(), 0f, 0f);

        entity.spawn(loc);

        trackedEntityIds.add(entity.getFakeEntityId());
        recordedEntities.put(entity.getUuid(), entity);

        WrapperPlayServerEntityMetadata meta = new WrapperPlayServerEntityMetadata(
                entity.getFakeEntityId(),
                Collections.emptyList()
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, meta);
    }

    public void spawnFakeDroppedItem(ItemStack stack, Location loc) {
        int entityId = SpigotReflectionUtil.generateEntityId();
        trackedEntityIds.add(entityId);

        com.github.retrooper.packetevents.protocol.item.ItemStack nmsStack = SpigotConversionUtil.fromBukkitItemStack(stack);

        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                entityId,
                UUID.randomUUID(),
                EntityTypes.ITEM,
                SpigotConversionUtil.fromBukkitLocation(loc),
                loc.getYaw(),
                0,
                null
        );

        EntityData<com.github.retrooper.packetevents.protocol.item.ItemStack> itemData = new EntityData<>(8, EntityDataTypes.ITEMSTACK, nmsStack);
        WrapperPlayServerEntityMetadata meta = new WrapperPlayServerEntityMetadata(
                entityId,
                Collections.singletonList(itemData)
        );

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, spawn);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, meta);
    }
}
