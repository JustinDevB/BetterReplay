package me.justindevb.replay.util;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.MojangAPIUtil;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SpawnFakePlayer {

    private final int entityId;
    private final UUID profileUuid;
    private final String name;
    private final Location spawnLocation;
    private final Player viewer;

    private final UUID fakeUuid;

    public SpawnFakePlayer(UUID profileUuid, String name, Location spawnLocation, Player viewer, int entityId) {
      //  this.entityId = SpigotReflectionUtil.generateEntityId();
        this.profileUuid = profileUuid;
        this.name = name;
        this.spawnLocation = spawnLocation;
        this.viewer = viewer;
        this.entityId = entityId;

        this.fakeUuid = UUID.randomUUID();

        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> playerInfoList = new ArrayList<>();

        UserProfile userProfile = new UserProfile(fakeUuid, name, MojangAPIUtil.requestPlayerTextureProperties(profileUuid));

        playerInfoList.add(new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(userProfile));

        WrapperPlayServerPlayerInfoUpdate infoUpdatePacket = new WrapperPlayServerPlayerInfoUpdate(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER, playerInfoList);

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, infoUpdatePacket);

        WrapperPlayServerSpawnEntity spawnEntityPacket = new WrapperPlayServerSpawnEntity(
                entityId,
                fakeUuid,
                EntityTypes.PLAYER,
                SpigotConversionUtil.fromBukkitLocation(spawnLocation),
                spawnLocation.getYaw(),
                0,
                new Vector3d(0, 0, 0)
        );

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, spawnEntityPacket);

       // List<EntityData<?>> skinMeta = new ArrayList<>();
        //skinMeta.add(new EntityData<>(17, EntityDataTypes.BYTE, (byte) (0x01 | 0x02 | 0x04 | 0x08 | 0x10 | 0x20 | 0x40)));
        List<EntityData<?>> skinMeta = new ArrayList<>();
        int SKIN_LAYER_INDEX = PacketEvents.getAPI().getPlayerManager().getClientVersion(viewer).isNewerThanOrEquals(ClientVersion.V_1_21_9) ? 16 : 17;

        byte skinFlags = (byte) (
                0x01 | // cape
                        0x02 | // jacket
                        0x04 | // left sleeve
                        0x08 | // right sleeve
                        0x10 | // left pants
                        0x20 | // right pants
                        0x40   // hat
        );
        skinMeta.add(new EntityData<>(
                SKIN_LAYER_INDEX,
                EntityDataTypes.BYTE,
                skinFlags
        ));

        WrapperPlayServerEntityMetadata metadataPacket = new WrapperPlayServerEntityMetadata(entityId, skinMeta);

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, metadataPacket);
    }

    public int getEntityId() {
        return entityId;
    }
}
