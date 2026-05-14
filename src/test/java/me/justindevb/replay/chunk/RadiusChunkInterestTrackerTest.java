package me.justindevb.replay.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadiusChunkInterestTrackerTest {

    private final RadiusChunkInterestTracker tracker = new RadiusChunkInterestTracker();

    @Test
    void discoverChunks_singlePlayerRadiusOne_returnsNineChunks() {
        Set<ChunkCoordinate> discovered = tracker.discoverChunks(List.of(new ChunkCoordinate("world", 10, 20)), 1);

        assertEquals(9, discovered.size());
        assertTrue(discovered.contains(new ChunkCoordinate("world", 10, 20)));
        assertTrue(discovered.contains(new ChunkCoordinate("world", 9, 19)));
        assertTrue(discovered.contains(new ChunkCoordinate("world", 11, 21)));
    }

    @Test
    void discoverChunks_overlappingPlayers_deduplicatesUnion() {
        Set<ChunkCoordinate> discovered = tracker.discoverChunks(List.of(
                new ChunkCoordinate("world", 0, 0),
                new ChunkCoordinate("world", 1, 0)), 1);

        assertEquals(12, discovered.size());
    }

    @Test
    void discoverChunks_farApartPlayers_keepsSeparateWindows() {
        Set<ChunkCoordinate> discovered = tracker.discoverChunks(List.of(
                new ChunkCoordinate("world", 0, 0),
                new ChunkCoordinate("world", 100, 100)), 1);

        assertEquals(18, discovered.size());
    }
}