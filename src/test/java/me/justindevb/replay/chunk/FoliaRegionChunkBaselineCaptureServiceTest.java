package me.justindevb.replay.chunk;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FoliaRegionChunkBaselineCaptureServiceTest {

    @Test
    void capture_whenRegionIsNotOwned_schedulesCaptureAtChunkLocation() throws Exception {
        World world = mock(World.class);
        ChunkCoordinate coordinate = new ChunkCoordinate("world", 1698, 2351);
        CapturedChunkBaseline expected = new CapturedChunkBaseline(coordinate, new byte[] {1, 2, 3});
        List<Location> scheduledLocations = new ArrayList<>();
        AtomicBoolean runningScheduledTask = new AtomicBoolean(false);

        FoliaRegionChunkBaselineCaptureService service = new FoliaRegionChunkBaselineCaptureService(
                capturedCoordinate -> {
                    assertTrue(runningScheduledTask.get());
                    assertEquals(coordinate, capturedCoordinate);
                    return expected;
                },
                (location, task) -> {
                    scheduledLocations.add(location);
                    runningScheduledTask.set(true);
                    task.run();
                    runningScheduledTask.set(false);
                    return CompletableFuture.completedFuture(null);
                },
                (ignoredWorld, ignoredCoordinate) -> false);

        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            assertSame(expected, service.capture(coordinate));
        }

        assertEquals(1, scheduledLocations.size());
        Location scheduledLocation = scheduledLocations.getFirst();
        assertSame(world, scheduledLocation.getWorld());
        assertEquals(1698 << 4, scheduledLocation.getBlockX());
        assertEquals(2351 << 4, scheduledLocation.getBlockZ());
    }

    @Test
    void capture_whenRegionIsAlreadyOwned_capturesInline() throws Exception {
        World world = mock(World.class);
        ChunkCoordinate coordinate = new ChunkCoordinate("world", 4, 7);
        CapturedChunkBaseline expected = new CapturedChunkBaseline(coordinate, new byte[] {9});
        AtomicBoolean scheduled = new AtomicBoolean(false);

        FoliaRegionChunkBaselineCaptureService service = new FoliaRegionChunkBaselineCaptureService(
                capturedCoordinate -> expected,
                (location, task) -> {
                    scheduled.set(true);
                    return CompletableFuture.completedFuture(null);
                },
                (ignoredWorld, ignoredCoordinate) -> true);

        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            assertSame(expected, service.capture(coordinate));
        }

        assertFalse(scheduled.get());
    }
}
