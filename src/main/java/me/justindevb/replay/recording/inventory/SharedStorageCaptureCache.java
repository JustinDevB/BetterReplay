package me.justindevb.replay.recording.inventory;

import me.justindevb.replay.recording.inventory.InventoryCaptureService.CapturedInventoryStorageSnapshot;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SharedStorageCaptureCache {

    private final Map<UUID, CachedStorageSnapshot> cachedStorage = new ConcurrentHashMap<>();

    public CapturedInventoryStorageSnapshot captureStorage(Player player,
                                                           int currentTick,
                                                           int maxAgeTicks,
                                                           boolean forceFreshCapture,
                                                           InventoryCaptureService captureService) {
        UUID playerUuid = player.getUniqueId();
        if (!forceFreshCapture) {
            CachedStorageSnapshot cached = cachedStorage.get(playerUuid);
            if (cached != null && currentTick - cached.tick() <= maxAgeTicks) {
                return cached.snapshot();
            }
        }

        CapturedInventoryStorageSnapshot snapshot = captureService.captureStorage(player);
        cachedStorage.put(playerUuid, new CachedStorageSnapshot(currentTick, snapshot));
        return snapshot;
    }

    public void invalidate(UUID playerUuid) {
        cachedStorage.remove(playerUuid);
    }

    private record CachedStorageSnapshot(int tick, CapturedInventoryStorageSnapshot snapshot) {
    }
}
