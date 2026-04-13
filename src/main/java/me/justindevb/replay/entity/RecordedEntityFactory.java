package me.justindevb.replay.entity;

import me.justindevb.replay.recording.TimelineEvent;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.UUID;

public class RecordedEntityFactory {
    public static RecordedEntity create(TimelineEvent event, Player viewer) {
        String uuidStr = event.uuid();
        if (uuidStr == null) {
            System.out.println("Malformed event: missing UUID");
            return null;
        }
        UUID uuid = UUID.fromString(uuidStr);

        return switch (event) {
            case TimelineEvent.PlayerMove e -> new RecordedPlayer(
                    uuid, e.name() != null ? e.name() : "Unknown", EntityType.PLAYER, viewer);
            case TimelineEvent.EntityMove e -> createFromEtype(uuid, e.etype(), viewer);
            case TimelineEvent.EntitySpawn e -> createFromEtype(uuid, e.etype(), viewer);
            case TimelineEvent.EntityDeath e -> createFromEtype(uuid, e.etype(), viewer);
            default -> {
                System.out.println("Cannot create entity from event type: " + event.getClass().getSimpleName());
                yield null;
            }
        };
    }

    private static RecordedEntity createFromEtype(UUID uuid, String etypeStr, Player viewer) {
        if (etypeStr == null) {
            System.out.println("Malformed event: missing entity type for UUID " + uuid);
            return null;
        }
        try {
            EntityType type = EntityType.valueOf(etypeStr);
            if (type == EntityType.PLAYER) {
                return new RecordedPlayer(uuid, "Unknown", type, viewer);
            }
            return new RecordedMob(uuid, type, viewer);
        } catch (IllegalArgumentException e) {
            System.out.println("Unknown entity type '" + etypeStr + "' for UUID " + uuid);
            return null;
        }
    }
}

