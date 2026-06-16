package me.justindevb.replay.recording;

import com.tcoded.folialib.FoliaLib;
import me.justindevb.replay.chunk.ChunkCoordinate;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Collects tracked player chunk positions from each player's owning Folia region.
 */
public final class FoliaTrackedChunkCollector {

    @FunctionalInterface
    interface PlayerResolver {
        Player resolve(UUID uuid);
    }

    @FunctionalInterface
    interface EntityTaskScheduler {
        CompletableFuture<?> schedule(Player player, Runnable task);
    }

    private final PlayerResolver playerResolver;
    private final EntityTaskScheduler entityTaskScheduler;

    public FoliaTrackedChunkCollector(FoliaLib foliaLib) {
        this(
                playerResolver(foliaLib),
                entityTaskScheduler(foliaLib));
    }

    FoliaTrackedChunkCollector(PlayerResolver playerResolver, EntityTaskScheduler entityTaskScheduler) {
        this.playerResolver = Objects.requireNonNull(playerResolver, "playerResolver");
        this.entityTaskScheduler = Objects.requireNonNull(entityTaskScheduler, "entityTaskScheduler");
    }

    public Set<ChunkCoordinate> collectTrackedPlayerChunks(EntityTracker tracker) throws IOException {
        Set<ChunkCoordinate> trackedChunks = ConcurrentHashMap.newKeySet();
        CompletableFuture<?>[] tasks = tracker.getTrackedPlayers().stream()
                .map(playerResolver::resolve)
                .filter(Objects::nonNull)
                .map(player -> entityTaskScheduler.schedule(player, () -> collectPlayerChunk(player, trackedChunks)))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(tasks).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while collecting tracked player chunks", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Failed to collect tracked player chunks", cause);
        }
        return Set.copyOf(trackedChunks);
    }

    private static void collectPlayerChunk(Player player, Set<ChunkCoordinate> trackedChunks) {
        if (!player.isOnline()) {
            return;
        }
        Location location = player.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }
        trackedChunks.add(new ChunkCoordinate(
                location.getWorld().getName(),
                Math.floorDiv(location.getBlockX(), 16),
                Math.floorDiv(location.getBlockZ(), 16)));
    }

    private static PlayerResolver playerResolver(FoliaLib foliaLib) {
        Objects.requireNonNull(foliaLib, "foliaLib");
        return uuid -> foliaLib.getScheduler().getPlayer(uuid);
    }

    private static EntityTaskScheduler entityTaskScheduler(FoliaLib foliaLib) {
        Objects.requireNonNull(foliaLib, "foliaLib");
        return (player, task) -> foliaLib.getScheduler().runAtEntity(player, ignored -> task.run());
    }
}
