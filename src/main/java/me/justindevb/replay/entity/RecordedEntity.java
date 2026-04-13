package me.justindevb.replay.entity;

import me.justindevb.replay.Replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.UUID;

public abstract class RecordedEntity {
    protected final UUID uuid;
    protected final EntityType type;
    protected final Player viewer;
    protected int fakeEntityId;
    protected Location currentLocation;
    private boolean destroyed = false;

    protected RecordedEntity(UUID uuid, EntityType type, Player viewer) {
        this.uuid = uuid;
        this.type = type;
        this.viewer = viewer;
        this.fakeEntityId = SpigotReflectionUtil.generateEntityId();
    }
    public int getFakeEntityId() {
        return fakeEntityId;
    }

    public abstract void spawn(Location location);
    public abstract void moveTo(Location location);
    public void destroy() {
        if (destroyed) return;
        destroyed = true;

        if (fakeEntityId > 0) {
            try {
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                        new WrapperPlayServerDestroyEntities(new int[]{fakeEntityId}));
            } catch (IndexOutOfBoundsException e) {
                Replay.getInstance().getLogger().warning("Tried to destroy entity " + fakeEntityId + " but it doesn't exist for viewer " + viewer.getName());
            }
        }

        if (this instanceof RecordedPlayer rp) {
            UUID fakeProfileUuid = rp.getFakeProfileUuid();
            if (fakeProfileUuid != null) {
                WrapperPlayServerPlayerInfoRemove remove =
                        new WrapperPlayServerPlayerInfoRemove(Collections.singletonList(fakeProfileUuid));
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, remove);
            }
        }
    }

    public void showDamage() {
        if (fakeEntityId == 0) return;

        WrapperPlayServerEntityStatus packet = new WrapperPlayServerEntityStatus(fakeEntityId, (byte) 2);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

    public void showDeath() {
        if (fakeEntityId == 0) return;

        if (this instanceof RecordedPlayer rp) {
            viewer.sendMessage("[BetterReplay] " + rp.getName() + " died");
        }

        WrapperPlayServerEntityStatus packet = new WrapperPlayServerEntityStatus(fakeEntityId, (byte) 3);
        packet.setStatus(3);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    public UUID getUuid() {
        return uuid;
    }

    public Location getCurrentLocation() {
        return currentLocation;
    }
}
