package me.justindevb.replay.entity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import me.justindevb.replay.Replay;
import me.justindevb.replay.util.EntityTypeMapper;
import me.justindevb.replay.util.spawning.SpawnFakeMob;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.UUID;

public class RecordedMob extends RecordedEntity {

    public RecordedMob(UUID uuid, EntityType type, Player viewer) {
        super(uuid, type, viewer);
    }

    @Override
    public void spawn(Location loc) {

        com.github.retrooper.packetevents.protocol.entity.type.EntityType peType = EntityTypeMapper.get(type);

        if (peType == null) {
            System.out.println("Unsupported mob type for replay: " + type);
            return;
        }

        new SpawnFakeMob(peType, loc, viewer, fakeEntityId);

    }

    @Override
    public void moveTo(Location loc) {
        WrapperPlayServerEntityTeleport tp = new WrapperPlayServerEntityTeleport(
                fakeEntityId,
                SpigotConversionUtil.fromBukkitLocation(loc),
                true
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, tp);

        byte headYaw = (byte) ((loc.getYaw() * 256f) / 360f);
        WrapperPlayServerEntityHeadLook headLook = new WrapperPlayServerEntityHeadLook(fakeEntityId, headYaw);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, headLook);
        super.currentLocation = loc;
    }
}

