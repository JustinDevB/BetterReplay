package me.justindevb.replay.recording;

import me.justindevb.replay.chunk.ChunkCoordinate;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FoliaTrackedChunkCollectorTest {

    @Test
    void collectTrackedPlayerChunks_readsPlayerLocationInsideEntityTask() throws Exception {
        UUID firstUuid = UUID.randomUUID();
        UUID secondUuid = UUID.randomUUID();
        Player firstPlayer = mock(Player.class);
        Player secondPlayer = mock(Player.class);
        World world = mock(World.class);
        AtomicBoolean insideEntityTask = new AtomicBoolean(false);

        when(firstPlayer.getUniqueId()).thenReturn(firstUuid);
        when(secondPlayer.getUniqueId()).thenReturn(secondUuid);
        when(firstPlayer.isOnline()).thenAnswer(invocation -> {
            assertTrue(insideEntityTask.get());
            return true;
        });
        when(secondPlayer.isOnline()).thenAnswer(invocation -> {
            assertTrue(insideEntityTask.get());
            return true;
        });
        when(firstPlayer.getLocation()).thenAnswer(invocation -> {
            assertTrue(insideEntityTask.get());
            return new Location(world, 31.9, 64, 31.9);
        });
        when(secondPlayer.getLocation()).thenAnswer(invocation -> {
            assertTrue(insideEntityTask.get());
            return new Location(world, 16.0, 64, 0.0);
        });
        when(world.getName()).thenReturn("world");

        EntityTracker tracker = new EntityTracker(List.of(firstPlayer, secondPlayer));
        FoliaTrackedChunkCollector collector = new FoliaTrackedChunkCollector(
                uuid -> uuid.equals(firstUuid) ? firstPlayer : secondPlayer,
                (player, task) -> {
                    assertTrue(player == firstPlayer || player == secondPlayer);
                    insideEntityTask.set(true);
                    task.run();
                    insideEntityTask.set(false);
                    return CompletableFuture.completedFuture(null);
                });

        Set<ChunkCoordinate> trackedChunks = collector.collectTrackedPlayerChunks(tracker);

        assertEquals(Set.of(
                new ChunkCoordinate("world", 1, 1),
                new ChunkCoordinate("world", 1, 0)), trackedChunks);
    }

    @Test
    void collectTrackedPlayerChunks_ignoresPlayersMissingFromResolver() throws Exception {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        EntityTracker tracker = new EntityTracker(List.of(player));
        FoliaTrackedChunkCollector collector = new FoliaTrackedChunkCollector(
                uuid -> null,
                (resolvedPlayer, task) -> {
                    throw new AssertionError("missing players must not be scheduled");
                });

        assertEquals(Set.of(), collector.collectTrackedPlayerChunks(tracker));
    }
}
