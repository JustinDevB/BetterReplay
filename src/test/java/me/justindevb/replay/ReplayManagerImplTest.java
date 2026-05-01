package me.justindevb.replay;

import me.justindevb.replay.api.ReplayExportQuery;
import me.justindevb.replay.chunk.ReplayChunkData;
import me.justindevb.replay.storage.ReplayDeleteResult;
import me.justindevb.replay.storage.ReplayPlaybackData;
import me.justindevb.replay.storage.ReplayProtectionResult;
import me.justindevb.replay.storage.ReplayStorage;
import me.justindevb.replay.storage.ReplayStorageType;
import me.justindevb.replay.storage.ReplaySummary;
import me.justindevb.replay.util.ReplayCache;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReplayManagerImplTest {

    @Mock private Replay plugin;
    @Mock private RecorderManager recorderManager;
    @Mock private ReplayStorage storage;
    @Mock private ReplayCache replayCache;

    private ReplayManagerImpl manager;

    @BeforeEach
    void setUp() {
        lenient().when(plugin.getReplayStorage()).thenReturn(storage);
        lenient().when(plugin.getReplayCache()).thenReturn(replayCache);
        manager = new ReplayManagerImpl(plugin, recorderManager);
    }

    @Test
    void startRecording_delegatesToRecorderManager() {
        Player p = mock(Player.class);
        when(recorderManager.startSession("test", List.of(p), 60)).thenReturn(true);

        boolean result = manager.startRecording("test", List.of(p), 60);
        assertTrue(result);
        verify(recorderManager).startSession("test", List.of(p), 60);
    }

    @Test
    void stopRecording_delegatesAndRefreshesCache() {
        when(recorderManager.stopSession("test", true)).thenReturn(true);
        when(storage.listReplays()).thenReturn(CompletableFuture.completedFuture(List.of("test")));

        boolean result = manager.stopRecording("test", true);
        assertTrue(result);
        verify(recorderManager).stopSession("test", true);
    }

    @Test
    void stopRecording_noSave_doesNotRefreshCache() {
        when(recorderManager.stopSession("test", false)).thenReturn(true);

        boolean result = manager.stopRecording("test", false);
        assertTrue(result);
        verify(storage, never()).listReplays();
    }

    @Test
    void stopRecording_nonExistent_returnsFalse() {
        when(recorderManager.stopSession("nope", true)).thenReturn(false);

        boolean result = manager.stopRecording("nope", true);
        assertFalse(result);
    }

    @Test
    void getActiveRecordings_delegatesToRecorderManager() {
        java.util.Map<String, RecordingSession> sessions = new java.util.HashMap<>();
        sessions.put("session1", mock(RecordingSession.class));
        when(recorderManager.getActiveSessions()).thenReturn(sessions);

        Collection<String> names = manager.getActiveRecordings();
        assertTrue(names.contains("session1"));
    }

    @Test
    void listSavedReplays_delegatesToStorage() {
        when(storage.listReplays()).thenReturn(CompletableFuture.completedFuture(List.of("r1", "r2")));

        List<String> result = manager.listSavedReplays().join();
        assertEquals(List.of("r1", "r2"), result);
    }

    @Test
    void listSavedReplays_nullStorage_returnsEmptyList() {
        when(plugin.getReplayStorage()).thenReturn(null);
        manager = new ReplayManagerImpl(plugin, recorderManager);

        List<String> result = manager.listSavedReplays().join();
        assertTrue(result.isEmpty());
    }

    @Test
    void listSavedReplaySummaries_delegatesToStorage() {
        ReplaySummary summary = new ReplaySummary("r1", java.time.Instant.now(), 1L, false, null, null, ReplayStorageType.FILE);
        when(storage.listReplaySummaries()).thenReturn(CompletableFuture.completedFuture(List.of(summary)));

        List<ReplaySummary> result = manager.listSavedReplaySummaries().join();

        assertEquals(List.of(summary), result);
    }

    @Test
    void deleteSavedReplay_null_returnsNotFound() {
        ReplayDeleteResult result = manager.deleteSavedReplay(null).join();
        assertEquals(ReplayDeleteResult.NOT_FOUND, result);
    }

    @Test
    void deleteSavedReplay_blank_returnsNotFound() {
        ReplayDeleteResult result = manager.deleteSavedReplay("  ").join();
        assertEquals(ReplayDeleteResult.NOT_FOUND, result);
    }

    @Test
    void deleteSavedReplay_nullStorage_returnsNotFound() {
        when(plugin.getReplayStorage()).thenReturn(null);
        manager = new ReplayManagerImpl(plugin, recorderManager);

        ReplayDeleteResult result = manager.deleteSavedReplay("test").join();
        assertEquals(ReplayDeleteResult.NOT_FOUND, result);
    }

    @Test
    void deleteSavedReplay_existing_deletesAndRefreshesCache() {
        when(storage.deleteReplay("test")).thenReturn(CompletableFuture.completedFuture(ReplayDeleteResult.DELETED));
        when(storage.listReplays()).thenReturn(CompletableFuture.completedFuture(List.of()));

        ReplayDeleteResult result = manager.deleteSavedReplay("test").join();
        assertEquals(ReplayDeleteResult.DELETED, result);
        verify(replayCache).setReplays(List.of());
    }

    @Test
    void deleteSavedReplay_protected_doesNotRefreshCache() {
        when(storage.deleteReplay("test")).thenReturn(CompletableFuture.completedFuture(ReplayDeleteResult.PROTECTED));

        ReplayDeleteResult result = manager.deleteSavedReplay("test").join();

        assertEquals(ReplayDeleteResult.PROTECTED, result);
        verify(replayCache, never()).setReplays(anyList());
    }

    @Test
    void protectSavedReplay_delegatesToStorage() {
        when(storage.protectReplay(eq("test"), any(), eq("console")))
                .thenReturn(CompletableFuture.completedFuture(ReplayProtectionResult.UPDATED));

        ReplayProtectionResult result = manager.protectSavedReplay("test", "console").join();

        assertEquals(ReplayProtectionResult.UPDATED, result);
    }

    @Test
    void unprotectSavedReplay_delegatesToStorage() {
        when(storage.unprotectReplay("test"))
                .thenReturn(CompletableFuture.completedFuture(ReplayProtectionResult.UPDATED));

        ReplayProtectionResult result = manager.unprotectSavedReplay("test").join();

        assertEquals(ReplayProtectionResult.UPDATED, result);
    }

    @Test
    void stopReplay_nonReplaySession_returnsFalse() {
        assertFalse(manager.stopReplay("not a session"));
    }

    @Test
    void getCachedReplayNames_delegatesToCache() {
        when(replayCache.getReplays()).thenReturn(List.of("cached1", "cached2"));

        assertEquals(List.of("cached1", "cached2"), manager.getCachedReplayNames());
    }

    @Test
    void startReplay_nullViewer_returnsEmpty() {
        Optional<ReplaySession> result = manager.startReplay("test", null).join();
        assertTrue(result.isEmpty());
    }

    @Test
    void startReplay_nullName_returnsEmpty() {
        Player viewer = mock(Player.class);
        Optional<ReplaySession> result = manager.startReplay(null, viewer).join();
        assertTrue(result.isEmpty());
    }

    @Test
    void startReplay_emptyName_returnsEmpty() {
        Player viewer = mock(Player.class);
        Optional<ReplaySession> result = manager.startReplay("", viewer).join();
        assertTrue(result.isEmpty());
    }

    @Test
    void startReplay_usesReplayPlaybackDataLoadingPath() {
        Player viewer = mock(Player.class);
        when(storage.replayExists("test")).thenReturn(CompletableFuture.completedFuture(true));
        when(storage.loadReplayData("test")).thenReturn(CompletableFuture.completedFuture(
                new ReplayPlaybackData(List.of(), ReplayChunkData.NONE)));

        try (org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::isPrimaryThread).thenReturn(true);
            Optional<ReplaySession> result = manager.startReplay("test", viewer).join();
            assertTrue(result.isEmpty());
        }

        verify(storage).loadReplayData("test");
        verify(storage, never()).loadReplay("test");
    }

    @Test
    void getSavedReplayFile_withQuery_delegatesToStorage() {
        File file = mock(File.class);
        ReplayExportQuery query = new ReplayExportQuery("Steve", 10, 40);
        when(file.exists()).thenReturn(true);
        when(storage.getReplayFile("test", query)).thenReturn(CompletableFuture.completedFuture(file));

        Optional<File> result = manager.getSavedReplayFile("test", query).join();

        assertTrue(result.isPresent());
        assertSame(file, result.get());
    }
}
