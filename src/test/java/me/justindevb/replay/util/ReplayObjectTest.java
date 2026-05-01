package me.justindevb.replay.util;

import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.ReplayDeleteResult;
import me.justindevb.replay.storage.ReplayStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReplayObjectTest {

    @Mock
    private ReplayStorage storage;

    private ReplayObject replayObject;
    private List<TimelineEvent> timeline;

    @BeforeEach
    void setUp() {
        timeline = List.of(
                new TimelineEvent.PlayerQuit(0, "uuid-1"),
                new TimelineEvent.PlayerQuit(5, "uuid-2")
        );
        replayObject = new ReplayObject("test-replay", timeline, storage);
    }

    @Test
    void constructorSetsFields() {
        assertEquals("test-replay", replayObject.getName());
        assertSame(timeline, replayObject.getTimeline());
    }

    @Test
    void setTimeline_replacesExisting() {
        List<TimelineEvent> newTimeline = List.of(new TimelineEvent.PlayerQuit(10, "uuid-3"));
        replayObject.setTimeline(newTimeline);
        assertSame(newTimeline, replayObject.getTimeline());
    }

    @Test
    void save_delegatesToStorage() {
        when(storage.saveReplay("test-replay", timeline))
                .thenReturn(CompletableFuture.completedFuture(null));

        CompletableFuture<Void> result = replayObject.save();

        assertNotNull(result);
        assertDoesNotThrow(() -> result.join());
        verify(storage).saveReplay("test-replay", timeline);
    }

    @Test
    void load_updatesTimeline() {
        List<TimelineEvent> loadedTimeline = List.of(new TimelineEvent.PlayerQuit(99, "loaded"));
        when(storage.loadReplay("test-replay"))
                .thenReturn(CompletableFuture.completedFuture(loadedTimeline));

        replayObject.load().join();

        assertSame(loadedTimeline, replayObject.getTimeline());
        verify(storage).loadReplay("test-replay");
    }

    @Test
    void load_nullResult_keepsExistingTimeline() {
        when(storage.loadReplay("test-replay"))
                .thenReturn(CompletableFuture.completedFuture(null));

        replayObject.load().join();

        assertSame(timeline, replayObject.getTimeline());
    }

    @Test
    void delete_delegatesToStorage() {
        when(storage.deleteReplay("test-replay"))
            .thenReturn(CompletableFuture.completedFuture(ReplayDeleteResult.DELETED));

        ReplayDeleteResult result = replayObject.delete().join();

        assertEquals(ReplayDeleteResult.DELETED, result);
        verify(storage).deleteReplay("test-replay");
    }

    @Test
    void exists_delegatesToStorage() {
        when(storage.replayExists("test-replay"))
                .thenReturn(CompletableFuture.completedFuture(true));

        boolean result = replayObject.exists().join();

        assertTrue(result);
        verify(storage).replayExists("test-replay");
    }

    @Test
    void exists_falseWhenNotStored() {
        when(storage.replayExists("test-replay"))
                .thenReturn(CompletableFuture.completedFuture(false));

        boolean result = replayObject.exists().join();

        assertFalse(result);
    }
}
