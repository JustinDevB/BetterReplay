package me.justindevb.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.EventManager;
import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.ReplaySaveRequest;
import me.justindevb.replay.storage.ReplayStorage;
import me.justindevb.replay.storage.binary.BinaryReplayAppendLogHeader;
import me.justindevb.replay.storage.binary.BinaryReplayAppendLogWriter;
import me.justindevb.replay.util.ReplayCache;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RecorderManagerTest {

    private static final long RECORDING_STARTED_AT = 123456789L;

    private Replay plugin;
    private FoliaLib foliaLib;
    private PlatformScheduler scheduler;
    private PluginManager pluginManager;
    private RecorderManager manager;
    private ReplayStorage replayStorage;
    private ReplayCache replayCache;

    @TempDir
    Path tempDir;

    private MockedStatic<Bukkit> bukkitStatic;
    private MockedStatic<Replay> replayStatic;
    private MockedStatic<PacketEvents> packetEventsStatic;

    @BeforeEach
    void setUp() {
        plugin = mock(Replay.class);
        foliaLib = mock(FoliaLib.class);
        scheduler = mock(PlatformScheduler.class);
        pluginManager = mock(PluginManager.class);
        replayStorage = mock(ReplayStorage.class);
        replayCache = new ReplayCache();

        when(plugin.getFoliaLib()).thenReturn(foliaLib);
        when(foliaLib.getScheduler()).thenReturn(scheduler);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getReplayStorage()).thenReturn(replayStorage);
        when(plugin.getReplayCache()).thenReturn(replayCache);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(replayStorage.listReplays()).thenReturn(CompletableFuture.completedFuture(List.of("recovered")));
        doReturn(CompletableFuture.completedFuture(null))
            .when(replayStorage)
            .saveReplay(anyString(), any(ReplaySaveRequest.class));

        bukkitStatic = mockStatic(Bukkit.class);
        bukkitStatic.when(Bukkit::getPluginManager).thenReturn(pluginManager);
        bukkitStatic.when(Bukkit::getLogger).thenReturn(Logger.getLogger("test"));

        replayStatic = mockStatic(Replay.class);
        replayStatic.when(Replay::getInstance).thenReturn(plugin);

        PacketEventsAPI<?> packetApi = mock(PacketEventsAPI.class);
        EventManager eventManager = mock(EventManager.class);
        when(packetApi.getEventManager()).thenReturn(eventManager);
        when(eventManager.registerListener(any(), any())).thenReturn(mock(PacketListenerCommon.class));
        packetEventsStatic = mockStatic(PacketEvents.class);
        packetEventsStatic.when(PacketEvents::getAPI).thenReturn(packetApi);

        manager = new RecorderManager(plugin);
    }

    @AfterEach
    void tearDown() {
        packetEventsStatic.close();
        replayStatic.close();
        bukkitStatic.close();
    }

    @Test
    void startSession_newName_returnsTrue() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());

        WrappedTask mockTask = mock(WrappedTask.class);
        when(scheduler.runTimer(any(Runnable.class), anyLong(), anyLong())).thenReturn(mockTask);

        boolean result = manager.startSession("test", List.of(p), 30);
        assertTrue(result);
        assertTrue(manager.getActiveSessions().containsKey("test"));
    }

    @Test
    void startSession_duplicateName_returnsFalse() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());

        WrappedTask mockTask = mock(WrappedTask.class);
        when(scheduler.runTimer(any(Runnable.class), anyLong(), anyLong())).thenReturn(mockTask);

        manager.startSession("dup", List.of(p), -1);
        boolean result = manager.startSession("dup", List.of(p), -1);
        assertFalse(result);
    }

    @Test
    void stopSession_existing_returnsTrue() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());

        WrappedTask mockTask = mock(WrappedTask.class);
        when(scheduler.runTimer(any(Runnable.class), anyLong(), anyLong())).thenReturn(mockTask);
        when(scheduler.runNextTick(any())).thenReturn(CompletableFuture.completedFuture(null));

        manager.startSession("stop-test", List.of(p), -1);
        boolean stopped = manager.stopSession("stop-test", false);
        assertTrue(stopped);
        assertFalse(manager.getActiveSessions().containsKey("stop-test"));
    }

    @Test
    void stopSession_nonExistent_returnsFalse() {
        boolean result = manager.stopSession("nope", false);
        assertFalse(result);
    }

    @Test
    void shutdown_stopsAllSessions() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());

        WrappedTask mockTask = mock(WrappedTask.class);
        when(scheduler.runTimer(any(Runnable.class), anyLong(), anyLong())).thenReturn(mockTask);

        manager.startSession("s1", List.of(p), -1);
        manager.startSession("s2", List.of(p), -1);

        manager.shutdown();

        assertTrue(manager.getActiveSessions().isEmpty());
    }

    @Test
    void getActiveSessions_empty_initially() {
        assertTrue(manager.getActiveSessions().isEmpty());
    }

    @Test
    void recoverPendingAppendLogs_savesRecoveredReplayAndDeletesTempFile() throws Exception {
        File appendLog = createAppendLog("recovered", true);

        manager.recoverPendingAppendLogs();

        verify(replayStorage).saveReplay(eq("recovered"), argThat((ReplaySaveRequest request) ->
                request.recordingStartedAtEpochMillis() == RECORDING_STARTED_AT
                        && request.timeline().equals(List.of(new TimelineEvent.PlayerQuit(0, "uuid-1")))));
        assertFalse(appendLog.exists());
        assertEquals(List.of("recovered"), replayCache.getReplays());
    }

    @Test
    void recoverPendingAppendLogs_salvagesTruncatedTail() throws Exception {
        File appendLog = createAppendLog("recovered", false);

        manager.recoverPendingAppendLogs();

        verify(replayStorage).saveReplay(eq("recovered"), argThat((ReplaySaveRequest request) ->
                request.recordingStartedAtEpochMillis() == RECORDING_STARTED_AT
                        && request.timeline().equals(List.of(new TimelineEvent.PlayerQuit(0, "uuid-1")))));
        assertFalse(appendLog.exists());
    }

    private File createAppendLog(String name, boolean cleanEof) throws Exception {
        Path tempFolder = tempDir.resolve("replays").resolve(".tmp");
        Files.createDirectories(tempFolder);
        Path appendLogPath = tempFolder.resolve(name + ".appendlog");

        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(appendLogPath, new BinaryReplayAppendLogHeader(RECORDING_STARTED_AT))) {
            writer.append(new TimelineEvent.PlayerQuit(0, "uuid-1"));
            writer.flush();
        }

        if (!cleanEof) {
            Files.write(appendLogPath, new byte[]{(byte) 0x80}, StandardOpenOption.APPEND);
        }

        return appendLogPath.toFile();
    }
}
