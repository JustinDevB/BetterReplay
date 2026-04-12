package me.justindevb.replay.util.spawning;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

public class SpawnFakeMob {
   private final int entityId;
    private final UUID uuid;
    private final EntityType type;
    private final Location spawnLocation;
    private final Player viewer;

    public SpawnFakeMob(EntityType type, Location spawnLocation, Player viewer, int entityId) {
        this.entityId = entityId;
        this.uuid = UUID.randomUUID();
        this.type = type;
        this.spawnLocation = spawnLocation;
        this.viewer = viewer;

        spawn();
    }

    private void spawn() {
        WrapperPlayServerSpawnEntity spawnEntity = new WrapperPlayServerSpawnEntity(
                entityId,
                UUID.randomUUID(),
                type,
                SpigotConversionUtil.fromBukkitLocation(spawnLocation),
                spawnLocation.getYaw(),
                0,
                new Vector3d(0, 0, 0)
        );

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, spawnEntity);
    }

    public int getEntityId() {
        return entityId;
    }

    public UUID getUuid() {
        return uuid;
    }

    public Location getSpawnLocation() {
        return spawnLocation;
    }
}

