package me.justindevb.replay.recording;

import me.justindevb.replay.chunk.ChunkCoordinate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EntityTrackerTest {

    @Mock private Player player1;
    @Mock private Player player2;
    @Mock private World world;

    private UUID uuid1;
    private UUID uuid2;
    private EntityTracker tracker;

    @BeforeEach
    void setUp() {
        uuid1 = UUID.randomUUID();
        uuid2 = UUID.randomUUID();
        when(player1.getUniqueId()).thenReturn(uuid1);
        when(player2.getUniqueId()).thenReturn(uuid2);
        tracker = new EntityTracker(List.of(player1, player2));
    }

    @Test
    void constructor_tracksAllPlayers() {
        assertTrue(tracker.isTrackedPlayer(uuid1));
        assertTrue(tracker.isTrackedPlayer(uuid2));
    }

    @Test
    void isTrackedPlayer_falseForUnknown() {
        assertFalse(tracker.isTrackedPlayer(UUID.randomUUID()));
    }

    @Test
    void getTrackedPlayers_returnsAll() {
        assertEquals(2, tracker.getTrackedPlayers().size());
        assertTrue(tracker.getTrackedPlayers().contains(uuid1));
        assertTrue(tracker.getTrackedPlayers().contains(uuid2));
    }

    @Test
    void trackEntity_addsToTracked() {
        UUID entityUuid = UUID.randomUUID();
        tracker.trackEntity(entityUuid, EntityType.ZOMBIE);

        assertTrue(tracker.isEntityTracked(entityUuid));
        assertEquals(EntityType.ZOMBIE, tracker.getTrackedEntities().get(entityUuid));
    }

    @Test
    void isEntityTracked_falseForUntracked() {
        assertFalse(tracker.isEntityTracked(UUID.randomUUID()));
    }

    @Test
    void removeEntity_removesFromTracked() {
        UUID entityUuid = UUID.randomUUID();
        tracker.trackEntity(entityUuid, EntityType.SKELETON);
        tracker.removeEntity(entityUuid);

        assertFalse(tracker.isEntityTracked(entityUuid));
    }

    @Test
    void removePlayer_removesFromTracked() {
        tracker.removePlayer(uuid1);

        assertFalse(tracker.isTrackedPlayer(uuid1));
        assertTrue(tracker.isTrackedPlayer(uuid2));
    }

    @Test
    void clearPlayers_emptiesSet() {
        tracker.clearPlayers();

        assertFalse(tracker.isTrackedPlayer(uuid1));
        assertFalse(tracker.isTrackedPlayer(uuid2));
        assertTrue(tracker.getTrackedPlayers().isEmpty());
    }

    @Test
    void isNearbyTrackedPlayer_nullLocation_returnsFalse() {
        assertFalse(tracker.isNearbyTrackedPlayer(null));
    }

    @Test
    void isNearbyTrackedPlayer_nullWorld_returnsFalse() {
        Location loc = new Location(null, 0, 0, 0);
        assertFalse(tracker.isNearbyTrackedPlayer(loc));
    }

    @Test
    void isNearbyTrackedPlayer_withinRadius_returnsTrue() {
        Location playerLoc = new Location(world, 100, 64, 100);
        Location spawnLoc = new Location(world, 110, 64, 110);

        when(player1.getLocation()).thenReturn(playerLoc);
        when(player1.getWorld()).thenReturn(world);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid1)).thenReturn(player1);
            bukkit.when(() -> Bukkit.getPlayer(uuid2)).thenReturn(null);

            assertTrue(tracker.isNearbyTrackedPlayer(spawnLoc));
        }
    }

    @Test
    void isNearbyTrackedPlayer_outsideRadius_returnsFalse() {
        Location playerLoc = new Location(world, 100, 64, 100);
        // 32 blocks radius → >32 should be false
        Location spawnLoc = new Location(world, 200, 64, 200);

        when(player1.getLocation()).thenReturn(playerLoc);
        when(player1.getWorld()).thenReturn(world);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid1)).thenReturn(player1);
            bukkit.when(() -> Bukkit.getPlayer(uuid2)).thenReturn(null);

            assertFalse(tracker.isNearbyTrackedPlayer(spawnLoc));
        }
    }

    @Test
    void isNearbyTrackedPlayer_differentWorld_returnsFalse() {
        World otherWorld = mock(World.class);
        Location playerLoc = new Location(world, 100, 64, 100);
        Location spawnLoc = new Location(otherWorld, 100, 64, 100);

        when(player1.getLocation()).thenReturn(playerLoc);
        when(player1.getWorld()).thenReturn(world);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid1)).thenReturn(player1);
            bukkit.when(() -> Bukkit.getPlayer(uuid2)).thenReturn(null);

            assertFalse(tracker.isNearbyTrackedPlayer(spawnLoc));
        }
    }

    @Test
    void isNearbyTrackedPlayer_offlinePlayer_skipped() {
        Location spawnLoc = new Location(world, 100, 64, 100);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid1)).thenReturn(null);
            bukkit.when(() -> Bukkit.getPlayer(uuid2)).thenReturn(null);

            assertFalse(tracker.isNearbyTrackedPlayer(spawnLoc));
        }
    }

    @Test
    void clearPlayers_doesNotAffectEntities() {
        UUID entityUuid = UUID.randomUUID();
        tracker.trackEntity(entityUuid, EntityType.CREEPER);
        tracker.clearPlayers();

        assertTrue(tracker.isEntityTracked(entityUuid));
    }

    @Test
    void collectTrackedPlayerChunks_returnsUniqueChunkCoordinatesForOnlinePlayers() {
        Location playerOneLocation = new Location(world, 31.9, 64, 31.9);
        Location playerTwoLocation = new Location(world, 16.0, 64, 0.0);
        when(player1.isOnline()).thenReturn(true);
        when(player2.isOnline()).thenReturn(true);
        when(player1.getLocation()).thenReturn(playerOneLocation);
        when(player2.getLocation()).thenReturn(playerTwoLocation);
        when(world.getName()).thenReturn("world");

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid1)).thenReturn(player1);
            bukkit.when(() -> Bukkit.getPlayer(uuid2)).thenReturn(player2);

            Set<ChunkCoordinate> trackedChunks = tracker.collectTrackedPlayerChunks();

            assertEquals(Set.of(
                    new ChunkCoordinate("world", 1, 1),
                    new ChunkCoordinate("world", 1, 0)), trackedChunks);
        }
    }
}
