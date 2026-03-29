package me.justindevb.replay.util;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.MojangAPIUtil;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import me.justindevb.replay.Replay;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
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
        this.profileUuid = profileUuid;
        this.name = name;
        this.spawnLocation = spawnLocation;
        this.viewer = viewer;
        this.entityId = entityId;

        this.fakeUuid = UUID.randomUUID();

        spawn();

    }

    public void spawn() {

        Replay.getInstance().getFoliaLib().getScheduler().runAsync(task -> {
            UUID skinUuid = profileUuid;
            List<TextureProperty> textures = Collections.emptyList();

            if (profileUuid.version() == 4) {
                try {
                    textures = MojangAPIUtil.requestPlayerTextureProperties(skinUuid);
                } catch (Exception ignored) {
                }
            } else {
                skinUuid = FloodgateHook.getCorrectUUID(profileUuid);
                if (skinUuid != profileUuid)
                    textures = MojangAPIUtil.requestPlayerTextureProperties(skinUuid);
            }

            if (textures == null || textures.isEmpty()) {
                try {
                    textures = MojangAPIUtil.requestPlayerTextureProperties(
                            UUID.fromString("069a79f444e94726a5befca90e38aaf5") // Notch
                    );
                } catch (Exception ignored) {
                    textures = Collections.emptyList();
                }
            }

            UserProfile profile = new UserProfile(fakeUuid, name, textures);

            Replay.getInstance().getFoliaLib().getScheduler().runNextTick(syncTask -> {
                spawnNow(profile);
            });
        });
    }

    private void spawnNow(UserProfile profile) {
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> playerInfoList = new ArrayList<>();
        playerInfoList.add(new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(profile));

        WrapperPlayServerPlayerInfoUpdate infoPacket =
                new WrapperPlayServerPlayerInfoUpdate(
                        WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                        playerInfoList
                );

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, infoPacket);

        WrapperPlayServerSpawnEntity spawnPacket = new WrapperPlayServerSpawnEntity(
                entityId,
                fakeUuid,
                EntityTypes.PLAYER,
                SpigotConversionUtil.fromBukkitLocation(spawnLocation),
                spawnLocation.getYaw(),
                0,
                new Vector3d(0, 0, 0)
        );

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, spawnPacket);

        sendSkinMetadata();
    }

    public void sendSkinMetadata() {
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
