package me.justindevb.replay.util;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;

import java.util.EnumMap;
import java.util.Map;

public class EntityTypeMapper {

    private static final Map<org.bukkit.entity.EntityType, EntityType> ENTITY_TYPE_MAP = new EnumMap<>(org.bukkit.entity.EntityType.class);

    static {
        for (org.bukkit.entity.EntityType bukkitType : org.bukkit.entity.EntityType.values()) {
            EntityType peType = EntityTypes.getByName(bukkitType.name().toLowerCase());
            if (peType != null) ENTITY_TYPE_MAP.put(bukkitType, peType);
        }

      //  ENTITY_TYPE_MAP.put(org.bukkit.entity.EntityType.ITEM, EntityTypes.ITEM);
       // ENTITY_TYPE_MAP.put(org.bukkit.entity.EntityType.EXPERIENCE_ORB, EntityTypes.EXPERIENCE_ORB);
    }

    public static EntityType get(org.bukkit.entity.EntityType type) {
        return ENTITY_TYPE_MAP.get(type);
    }
}
