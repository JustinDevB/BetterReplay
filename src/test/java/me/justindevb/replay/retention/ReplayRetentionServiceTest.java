package me.justindevb.replay.retention;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import me.justindevb.replay.storage.ReplayDeleteResult;
import me.justindevb.replay.storage.ReplayStorage;
import me.justindevb.replay.storage.ReplayStorageType;
import me.justindevb.replay.storage.ReplaySummary;
import me.justindevb.replay.util.ReplayCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReplayRetentionServiceTest {

    @Mock private ReplayStorage storage;
    @Mock private FoliaLib foliaLib;
    @Mock private PlatformScheduler scheduler;
    @Mock private ReplayCache replayCache;
    @Mock private WrappedTask wrappedTask;

    private Logger logger;
    private Clock clock;

    @BeforeEach
    void setUp() {
        logger = Logger.getLogger("ReplayRetentionServiceTest");
        clock = Clock.fixed(Instant.parse("2026-04-29T20:00:00Z"), ZoneOffset.UTC);
        when(foliaLib.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTimer(any(Runnable.class), anyLong(), anyLong())).thenReturn(wrappedTask);
        when(storage.listReplays()).thenReturn(CompletableFuture.completedFuture(List.of()));
    }

    @Test
    void start_disabledDoesNotScheduleTask() {
        ReplayRetentionService service = new ReplayRetentionService(storage, foliaLib, logger,
                new RetentionPolicy(false, Duration.ofDays(30), Duration.ofHours(1), false, true), replayCache, clock);

        service.start();

                verify(scheduler, never()).runTimer(any(Runnable.class), anyLong(), anyLong());
    }

    @Test
    void start_enabledSchedulesTask() {
        ReplayRetentionService service = new ReplayRetentionService(storage, foliaLib, logger,
                new RetentionPolicy(true, Duration.ofDays(30), Duration.ofHours(1), false, true), replayCache, clock);

        service.start();

                verify(scheduler).runTimer(any(Runnable.class), anyLong(), anyLong());
    }

    @Test
    void runRetentionPass_deletesExpiredUnprotectedAndRefreshesCache() {
        when(storage.listReplaySummaries()).thenReturn(CompletableFuture.completedFuture(List.of(
                new ReplaySummary("expired", Instant.parse("2026-03-01T00:00:00Z"), 10L, false, null, null, ReplayStorageType.FILE),
                new ReplaySummary("fresh", Instant.parse("2026-04-20T00:00:00Z"), 10L, false, null, null, ReplayStorageType.FILE)
        )));
        when(storage.deleteReplay("expired")).thenReturn(CompletableFuture.completedFuture(ReplayDeleteResult.DELETED));
        when(storage.listReplays()).thenReturn(CompletableFuture.completedFuture(List.of("fresh")));

        ReplayRetentionService service = new ReplayRetentionService(storage, foliaLib, logger,
                new RetentionPolicy(true, Duration.ofDays(30), Duration.ofHours(1), false, false), replayCache, clock);

        ReplayRetentionRunResult result = service.runRetentionPass();

        assertEquals(2, result.scannedReplays());
        assertEquals(1, result.expiredCandidates());
        assertEquals(1, result.deletedReplays());
        assertEquals(0, result.skippedProtectedReplays());
        assertEquals(0, result.failedDeletes());
        verify(replayCache).setReplays(List.of("fresh"));
    }

    @Test
    void runRetentionPass_skipsExpiredProtectedReplay() {
        when(storage.listReplaySummaries()).thenReturn(CompletableFuture.completedFuture(List.of(
                new ReplaySummary("protected", Instant.parse("2026-03-01T00:00:00Z"), 10L, true,
                        Instant.parse("2026-03-15T00:00:00Z"), "console", ReplayStorageType.FILE)
        )));

        ReplayRetentionService service = new ReplayRetentionService(storage, foliaLib, logger,
                new RetentionPolicy(true, Duration.ofDays(30), Duration.ofHours(1), false, false), replayCache, clock);

        ReplayRetentionRunResult result = service.runRetentionPass();

        assertEquals(1, result.expiredCandidates());
        assertEquals(0, result.deletedReplays());
        assertEquals(1, result.skippedProtectedReplays());
        verify(storage, never()).deleteReplay("protected");
    }

    @Test
    void runRetentionPass_stopsAfterFirstFailureWhenConfigured() {
        when(storage.listReplaySummaries()).thenReturn(CompletableFuture.completedFuture(List.of(
                new ReplaySummary("missing", Instant.parse("2026-03-01T00:00:00Z"), 10L, false, null, null, ReplayStorageType.FILE),
                new ReplaySummary("second", Instant.parse("2026-03-02T00:00:00Z"), 10L, false, null, null, ReplayStorageType.FILE)
        )));
        when(storage.deleteReplay("missing")).thenReturn(CompletableFuture.completedFuture(ReplayDeleteResult.NOT_FOUND));

        ReplayRetentionService service = new ReplayRetentionService(storage, foliaLib, logger,
                new RetentionPolicy(true, Duration.ofDays(30), Duration.ofHours(1), false, false), replayCache, clock);

        ReplayRetentionRunResult result = service.runRetentionPass();

        assertEquals(1, result.failedDeletes());
        verify(storage, never()).deleteReplay("second");
    }
}