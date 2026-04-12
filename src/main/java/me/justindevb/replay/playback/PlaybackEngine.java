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
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;

import java.util.*;

import static me.justindevb.replay.playback.ReplayBlockManager.*;
import static me.justindevb.replay.util.io.ItemStackSerializer.deserializeItem;

/**
 * Dispatches replay timeline events to the appropriate RecordedEntity methods
 * and handles spawning of fake mobs/items.
 */
public class PlaybackEngine {

    private final Player viewer;
    private final Replay replay;
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
        this.replay = replay;
        this.trackedEntityIds = trackedEntityIds;
        this.deadEntities = deadEntities;
        this.recordedEntities = recordedEntities;
        this.blockManager = blockManager;
    }

    public void handleEvent(RecordedEntity entity, Map<String, Object> event) {
        String type = (String) event.get("type");
        if (type == null) return;

        switch (type) {
            case "player_move", "entity_move" -> {
                World world = Bukkit.getWorld(asString(event.get("world")));
                Double x = asDouble(event.get("x"));
                Double y = asDouble(event.get("y"));
                Double z = asDouble(event.get("z"));
                if (x == null || y == null || z == null) return;

                Location loc = new Location(world, x, y, z,
                        asFloat(event.get("yaw")), asFloat(event.get("pitch")));
                entity.moveTo(loc);

                if (event.containsKey("pose") && entity instanceof RecordedPlayer rp) {
                    String poseName = (String) event.get("pose");
                    if (poseName != null) {
                        try {
                            Pose pose = Pose.valueOf(poseName);
                            rp.setPose(pose);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            }
            case "sneak_start", "sneak_stop", "attack", "block_place", "block_break" -> {
                if (entity instanceof RecordedPlayer rp) {
                    switch (type) {
                        case "sneak_start", "sneak_stop" -> rp.updateSneak(type.equals("sneak_start"));
                        case "attack" -> rp.playAttackAnimation();
                        case "block_place" -> rp.showBlockPlace(event);
                        case "block_break" -> rp.showBlockBreak(event);
                    }
                }

                if ("block_place".equals(type) || "block_break".equals(type)) {
                    blockManager.applyReplayBlockChange(event, type, false);
                }
            }
            case "block_break_stage" -> {
                if (entity instanceof RecordedPlayer rp) {
                    rp.showBlockBreak(event);
                }
            }
            case "swing" -> {
                if (entity instanceof RecordedPlayer rp) {
                    String hand = (String) event.get("hand");
                    rp.playSwing(hand);
                }
            }
            case "damaged" -> {
                entity.showDamage(event);
            }
            case "sprint_start", "sprint_stop" -> {
                if (entity instanceof RecordedPlayer rp) {
                    rp.updateSprint(type.equals("sprint_start"));
                }
            }
            case "entity_death" -> {
                entity.showDeath(event);
                deadEntities.add(entity.getUuid());
                entity.destroy();
                recordedEntities.remove(entity.getUuid());
            }
            case "inventory_update" -> {
                if (entity instanceof RecordedPlayer rp) {
                    rp.updateInventory(event);
                }
            }
            case "item_drop" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> locMap = (Map<String, Object>) event.get("location");

                ItemStack stack = deserializeItem(event.get("item"));
                Location loc = deserializeLocation(locMap);

                if (stack != null && loc != null)
                    spawnFakeDroppedItem(stack, loc);
            }
            case "mob_spawn" -> {
                spawnFakeMob(entity, event);
            }
            case "player_quit" -> {
                UUID uuid = UUID.fromString((String) event.get("uuid"));

                recordedEntities.remove(uuid);
                if (entity == null) {
                    return;
                }

                entity.destroy();
                trackedEntityIds.remove(entity.getFakeEntityId());
            }
        }
    }

    public void spawnFakeMob(RecordedEntity entity, Map<String, Object> event) {
        Location loc = new Location(Bukkit.getWorld(asString(event.get("world"))),
                asDouble(event.get("x")),
                asDouble(event.get("y")),
                asDouble(event.get("z")),
                asFloat(event.get("yaw")),
                asFloat(event.get("pitch")));

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

    public Location deserializeLocation(Map<String, Object> map) {
        if (map == null)
            return null;
        double x = ((Number) map.get("x")).doubleValue();
        double y = ((Number) map.get("y")).doubleValue();
        double z = ((Number) map.get("z")).doubleValue();
        float yaw = map.get("yaw") instanceof Number n ? n.floatValue() : 0f;
        float pitch = map.get("pitch") instanceof Number n ? n.floatValue() : 0f;
        String world = map.get("world").toString();

        return new Location(Bukkit.getWorld(world), x, y, z, yaw, pitch);
    }
}
