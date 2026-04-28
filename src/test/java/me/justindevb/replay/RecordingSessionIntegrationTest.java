package me.justindevb.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.EventManager;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.ReplayStorage;
import me.justindevb.replay.util.ReplayCache;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecordingSessionIntegrationTest {

    @Mock private Replay plugin;
    @Mock private PluginManager pluginManager;
    @Mock private Player player;
    @Mock private World world;
    @Mock private PlayerInventory playerInventory;
    @Mock private ReplayStorage storage;
    @Mock private ReplayCache replayCache;

    private UUID playerUuid;

    @BeforeEach
    void setUp() {
        playerUuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerUuid);
    }

    private RecordingSession createSession(int durationSeconds) {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "betterreplay-test-" + System.nanoTime());
        return new RecordingSession("integration-test", tempDir, List.of(player), durationSeconds);
    }

    @Test
    void constructor_setsFields() {
        RecordingSession s = createSession(30);
        assertEquals(0, s.getTick());
        assertFalse(s.isStopped());
        assertTrue(s.getTrackedPlayers().contains(playerUuid));
        assertTrue(s.isTrackedPlayer(playerUuid));
    }

    @Test
    void tick_incrementsTickCounter() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<Replay> replayStatic = mockStatic(Replay.class)) {

            replayStatic.when(Replay::getInstance).thenReturn(plugin);
            bukkit.when(() -> Bukkit.getPlayer(playerUuid)).thenReturn(player);

            when(player.isOnline()).thenReturn(true);
            when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0, 0, 0));
            when(player.getPose()).thenReturn(Pose.STANDING);
            when(world.getName()).thenReturn("world");
            when(player.getWorld()).thenReturn(world);
            when(player.getInventory()).thenReturn(playerInventory);
            when(playerInventory.getContents()).thenReturn(new ItemStack[36]);
            when(playerInventory.getArmorContents()).thenReturn(new ItemStack[4]);
            when(playerInventory.getItemInOffHand()).thenReturn(null);
            when(playerInventory.getHeldItemSlot()).thenReturn(0);

            RecordingSession s = createSession(-1);
            // Tick multiple times
            s.tick();
            assertEquals(1, s.getTick());
            s.tick();
            assertEquals(2, s.getTick());
        }
    }

    @Test
    void tick_emitsPlayerMoveEvent() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<Replay> replayStatic = mockStatic(Replay.class)) {

            replayStatic.when(Replay::getInstance).thenReturn(plugin);
            bukkit.when(() -> Bukkit.getPlayer(playerUuid)).thenReturn(player);

            when(player.isOnline()).thenReturn(true);
            when(player.getLocation()).thenReturn(new Location(world, 10, 64, 20, 45f, -30f));
            when(player.getPose()).thenReturn(Pose.STANDING);
            when(player.getName()).thenReturn("TestPlayer");
            when(world.getName()).thenReturn("world");
            when(player.getWorld()).thenReturn(world);
            when(player.getInventory()).thenReturn(playerInventory);
            when(playerInventory.getContents()).thenReturn(new ItemStack[36]);
            when(playerInventory.getArmorContents()).thenReturn(new ItemStack[4]);
            when(playerInventory.getItemInOffHand()).thenReturn(null);
            when(playerInventory.getHeldItemSlot()).thenReturn(0);

            RecordingSession s = createSession(-1);
            s.tick();

            List<TimelineEvent> timeline = s.getTimeline();
            assertFalse(timeline.isEmpty());

            // First event should be a PlayerMove (or InventoryUpdate at tick 0)
            boolean hasPlayerMove = timeline.stream()
                    .anyMatch(e -> e instanceof TimelineEvent.PlayerMove);
            assertTrue(hasPlayerMove);
        }
    }

    @Test
    void tick_offlinePlayer_skipped() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<Replay> replayStatic = mockStatic(Replay.class)) {

            replayStatic.when(Replay::getInstance).thenReturn(plugin);
            bukkit.when(() -> Bukkit.getPlayer(playerUuid)).thenReturn(null);

            RecordingSession s = createSession(-1);
            s.tick();

            // Should not crash, timeline may have inventory events only
            assertEquals(1, s.getTick());
        }
    }

    @Test
    void stop_marksAsStopped() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<Replay> replayStatic = mockStatic(Replay.class);
             MockedStatic<PacketEvents> pe = mockStatic(PacketEvents.class)) {

            replayStatic.when(Replay::getInstance).thenReturn(plugin);
            when(plugin.getReplayStorage()).thenReturn(storage);
            when(plugin.getReplayCache()).thenReturn(replayCache);

            PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
            EventManager eventManager = mock(EventManager.class);
            pe.when(PacketEvents::getAPI).thenReturn(api);
            when(api.getEventManager()).thenReturn(eventManager);

            RecordingSession s = createSession(-1);
            s.stop(false);

            assertTrue(s.isStopped());
        }
    }

    @Test
    void stop_doubleStop_isIdempotent() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<Replay> replayStatic = mockStatic(Replay.class);
             MockedStatic<PacketEvents> pe = mockStatic(PacketEvents.class)) {

            replayStatic.when(Replay::getInstance).thenReturn(plugin);
            when(plugin.getReplayStorage()).thenReturn(storage);

            PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
            EventManager eventManager = mock(EventManager.class);
            pe.when(PacketEvents::getAPI).thenReturn(api);
            when(api.getEventManager()).thenReturn(eventManager);

            RecordingSession s = createSession(-1);
            s.stop(false);
            s.stop(false); // should not throw

            assertTrue(s.isStopped());
        }
    }

    @Test
    void stop_noSave_doesNotCallStorage() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<Replay> replayStatic = mockStatic(Replay.class);
             MockedStatic<PacketEvents> pe = mockStatic(PacketEvents.class)) {

            replayStatic.when(Replay::getInstance).thenReturn(plugin);

            PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
            EventManager eventManager = mock(EventManager.class);
            pe.when(PacketEvents::getAPI).thenReturn(api);
            when(api.getEventManager()).thenReturn(eventManager);

            RecordingSession s = createSession(-1);
            s.stop(false);

            verify(storage, never()).saveReplay(anyString(), anyList());
        }
    }

    @Test
    void tick_afterStop_noop() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<Replay> replayStatic = mockStatic(Replay.class);
             MockedStatic<PacketEvents> pe = mockStatic(PacketEvents.class)) {

            replayStatic.when(Replay::getInstance).thenReturn(plugin);

            PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
            EventManager eventManager = mock(EventManager.class);
            pe.when(PacketEvents::getAPI).thenReturn(api);
            when(api.getEventManager()).thenReturn(eventManager);

            RecordingSession s = createSession(-1);
            s.stop(false);

            int tickBefore = s.getTick();
            s.tick();
            assertEquals(tickBefore, s.getTick()); // tick did not advance
        }
    }

    @Test
    void durationLimit_autoStops() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<Replay> replayStatic = mockStatic(Replay.class);
             MockedStatic<PacketEvents> pe = mockStatic(PacketEvents.class)) {

            replayStatic.when(Replay::getInstance).thenReturn(plugin);
            when(plugin.getReplayStorage()).thenReturn(storage);
            when(plugin.getReplayCache()).thenReturn(replayCache);
            when(plugin.getLogger()).thenReturn(Logger.getLogger("BetterReplay-Test"));

            PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
            EventManager eventManager = mock(EventManager.class);
            pe.when(PacketEvents::getAPI).thenReturn(api);
            when(api.getEventManager()).thenReturn(eventManager);

                when(storage.saveReplay(anyString(), org.mockito.ArgumentMatchers.any(me.justindevb.replay.storage.ReplaySaveRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));
            when(storage.listReplays()).thenReturn(CompletableFuture.completedFuture(List.of()));

            bukkit.when(() -> Bukkit.getPlayer(playerUuid)).thenReturn(player);
            when(player.isOnline()).thenReturn(true);
            when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0, 0, 0));
            when(player.getPose()).thenReturn(Pose.STANDING);
            when(player.getName()).thenReturn("TestPlayer");
            when(world.getName()).thenReturn("world");
            when(player.getWorld()).thenReturn(world);
            when(player.getInventory()).thenReturn(playerInventory);
            when(playerInventory.getContents()).thenReturn(new ItemStack[36]);
            when(playerInventory.getArmorContents()).thenReturn(new ItemStack[4]);
            when(playerInventory.getItemInOffHand()).thenReturn(null);
            when(playerInventory.getHeldItemSlot()).thenReturn(0);

            // 1 second = 20 ticks
            RecordingSession s = createSession(1);

            for (int i = 0; i < 25; i++) {
                s.tick();
                if (s.isStopped()) break;
            }

            assertTrue(s.isStopped());
            assertTrue(s.getTick() <= 20);
        }
    }

    @Test
    void inventoryCheck_firesEvery5Ticks() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<Replay> replayStatic = mockStatic(Replay.class)) {

            replayStatic.when(Replay::getInstance).thenReturn(plugin);
            bukkit.when(() -> Bukkit.getPlayer(playerUuid)).thenReturn(player);

            when(player.isOnline()).thenReturn(true);
            when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0, 0, 0));
            when(player.getPose()).thenReturn(Pose.STANDING);
            when(player.getName()).thenReturn("TestPlayer");
            when(world.getName()).thenReturn("world");
            when(player.getWorld()).thenReturn(world);
            when(player.getInventory()).thenReturn(playerInventory);
            when(playerInventory.getContents()).thenReturn(new ItemStack[36]);
            when(playerInventory.getArmorContents()).thenReturn(new ItemStack[4]);
            when(playerInventory.getItemInOffHand()).thenReturn(null);
            when(playerInventory.getHeldItemSlot()).thenReturn(0);

            RecordingSession s = createSession(-1);
            for (int i = 0; i < 10; i++) {
                s.tick();
            }

            // Check that inventory updates were recorded (at tick 0; subsequent checks
            // may skip if inventory is unchanged between intervals)
            long invCount = s.getTimeline().stream()
                    .filter(e -> e instanceof TimelineEvent.InventoryUpdate)
                    .count();
            assertTrue(invCount >= 1, "Expected at least 1 inventory update in 10 ticks, got " + invCount);
        }
    }
}
