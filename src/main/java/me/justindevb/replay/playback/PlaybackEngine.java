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
            case TimelineEvent.Damaged e -> entity.showDamage();
            case TimelineEvent.SprintToggle e -> {
                if (entity instanceof RecordedPlayer rp) rp.updateSprint(e.sprinting());
            }
            case TimelineEvent.EntityDeath e -> {
                entity.showDeath();
                deadEntities.add(entity.getUuid());
                entity.destroy();
                recordedEntities.remove(entity.getUuid());
            }
            case TimelineEvent.InventoryUpdate e -> {
                if (entity instanceof RecordedPlayer rp) rp.updateInventory(e);
            }
            case TimelineEvent.ItemDrop e -> {
                ItemStack stack = deserializeItem(e.item());
                Location loc = (e.locWorld() != null)
                        ? new Location(Bukkit.getWorld(e.locWorld()), e.locX(), e.locY(), e.locZ(), e.locYaw(), e.locPitch())
                        : null;
                if (stack != null && loc != null) spawnFakeDroppedItem(stack, loc);
            }
            case TimelineEvent.EntitySpawn e -> spawnFakeMob(entity, e);
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
