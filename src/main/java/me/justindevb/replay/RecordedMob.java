package me.justindevb.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import me.justindevb.replay.util.SpawnFakeMob;
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
        com.github.retrooper.packetevents.protocol.entity.type.EntityType peType = switch (type) {
            case ZOMBIE -> EntityTypes.ZOMBIE;
            case SKELETON -> EntityTypes.SKELETON;
            case CREEPER -> EntityTypes.CREEPER;
            case COW -> EntityTypes.COW;
            case PIG -> EntityTypes.PIG;
            case CHICKEN -> EntityTypes.CHICKEN;
            case SHEEP -> EntityTypes.SHEEP;
            case ITEM -> EntityTypes.ITEM;
            case EXPERIENCE_ORB -> EntityTypes.EXPERIENCE_ORB;
            default -> null;
        };

        if (peType == null) {
            System.out.println("Unsupported mob type for replay: " + type);
            return;
        }

       // new SpawnFakeMob(type, loc, viewer);
        SpawnFakeMob fakeMob = new SpawnFakeMob(peType, loc, viewer, fakeEntityId);

        System.out.println("FakeEntityId=" + fakeEntityId);

    }

    @Override
    public void moveTo(Location loc) {
        // Teleport/move packet (position + rotation)
        WrapperPlayServerEntityTeleport tp = new WrapperPlayServerEntityTeleport(
                fakeEntityId,
                SpigotConversionUtil.fromBukkitLocation(loc),
                true
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, tp);

        // Head look packet so the head follows yaw (client expects separate head packet)
        byte headYaw = (byte) ((loc.getYaw() * 256f) / 360f);
        WrapperPlayServerEntityHeadLook headLook = new WrapperPlayServerEntityHeadLook(fakeEntityId, headYaw);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, headLook);
        super.currentLocation = loc;
    }
}

