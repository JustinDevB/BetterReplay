package me.justindevb.replay.entity;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

public class RecordedEntityFactory {
    public static RecordedEntity create(Map<String, Object> frame, Player viewer) {
        Object uuidObj = frame.get("uuid");
        if (!(uuidObj instanceof String uuidStr)) {
            System.out.println("Malformed event: missing or invalid UUID");
            return null;
        }
        UUID uuid = UUID.fromString(uuidStr);

        Object typeObj = frame.get("etype");
        if (!(typeObj instanceof String typeStr)) {
            System.out.println("Malformed event: missing or invalid entity type for UUID " + uuid);
            return null;
        }

        EntityType type;
        try {
            type = EntityType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            System.out.println("Unknown entity type '" + typeStr + "' for UUID " + uuid);
            return null;
        }

        if (type == EntityType.PLAYER) {
            String name = (String) frame.get("name");
            if (name == null) name = "Unknown";
            return new RecordedPlayer(uuid, name, EntityType.PLAYER, viewer);
        } else {
            return new RecordedMob(uuid,  type, viewer);
        }
    }
}

