package me.justindevb.replay.recording;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Tracks which players and entities are being recorded.
 * Manages the tracked player set and nearby entity lifecycle.
 */
public class EntityTracker {

    private final Set<UUID> trackedPlayers;
    private final Map<UUID, EntityType> trackedEntities = new HashMap<>();

    private static final double NEARBY_RADIUS_SQUARED = 32.0 * 32.0;

    public EntityTracker(Collection<Player> players) {
        this.trackedPlayers = new HashSet<>();
        for (Player p : players) {
            this.trackedPlayers.add(p.getUniqueId());
        }
    }

    public boolean isTrackedPlayer(UUID uuid) {
        return trackedPlayers.contains(uuid);
    }

    public Set<UUID> getTrackedPlayers() {
        return trackedPlayers;
    }

    public Map<UUID, EntityType> getTrackedEntities() {
        return trackedEntities;
    }

    public void trackEntity(UUID uuid, EntityType type) {
        trackedEntities.put(uuid, type);
    }

    public boolean isEntityTracked(UUID uuid) {
        return trackedEntities.containsKey(uuid);
    }

    public void removeEntity(UUID uuid) {
        trackedEntities.remove(uuid);
    }

    public void removePlayer(UUID uuid) {
        trackedPlayers.remove(uuid);
    }

    public void clearPlayers() {
        trackedPlayers.clear();
    }

    /**
     * Returns true if the given location is within the tracking radius
     * of any currently tracked player.
     */
    public boolean isNearbyTrackedPlayer(Location spawnLoc) {
        if (spawnLoc == null || spawnLoc.getWorld() == null) {
            return false;
        }

        World spawnWorld = spawnLoc.getWorld();

        for (UUID uuid : Set.copyOf(trackedPlayers)) {
            Player tracked = Bukkit.getPlayer(uuid);
            if (tracked == null) continue;

            if (tracked.getWorld() != spawnWorld) continue;

            if (tracked.getLocation().distanceSquared(spawnLoc) <= NEARBY_RADIUS_SQUARED) {
                return true;
            }
        }

        return false;
    }
}
