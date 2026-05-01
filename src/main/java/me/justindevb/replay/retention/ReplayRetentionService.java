package me.justindevb.replay.retention;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import me.justindevb.replay.util.ReplayCache;
import me.justindevb.replay.storage.ReplayDeleteResult;
import me.justindevb.replay.storage.ReplayStorage;
import me.justindevb.replay.storage.ReplaySummary;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ReplayRetentionService {

    private final ReplayStorage storage;
    private final FoliaLib foliaLib;
    private final Logger logger;
    private final RetentionPolicy policy;
    private final ReplayCache replayCache;
    private final Clock clock;
    private final AtomicBoolean scanRunning = new AtomicBoolean(false);

    private WrappedTask retentionTask;

    public ReplayRetentionService(ReplayStorage storage, FoliaLib foliaLib, Logger logger, RetentionPolicy policy, ReplayCache replayCache) {
        this(storage, foliaLib, logger, policy, replayCache, Clock.systemUTC());
    }

    ReplayRetentionService(ReplayStorage storage, FoliaLib foliaLib, Logger logger, RetentionPolicy policy,
                           ReplayCache replayCache, Clock clock) {
        this.storage = storage;
        this.foliaLib = foliaLib;
        this.logger = logger;
        this.policy = policy;
        this.replayCache = replayCache;
        this.clock = clock;
    }

    public void start() {
        if (!policy.enabled() || retentionTask != null) {
            return;
        }
        long intervalTicks = Math.max(1L, Math.ceilDiv(policy.checkInterval().toMillis(), 50L));
        retentionTask = foliaLib.getScheduler().runTimer(this::scheduleAsyncPass, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (retentionTask != null) {
            retentionTask.cancel();
            retentionTask = null;
        }
    }

    public ReplayRetentionRunResult runRetentionPass() {
        if (!policy.enabled()) {
            return new ReplayRetentionRunResult(0, 0, 0, 0, 0);
        }

        List<ReplaySummary> summaries = storage.listReplaySummaries().join();
        Instant cutoff = clock.instant().minus(policy.maxAge());

        int expiredCandidates = 0;
        int deletedReplays = 0;
        int skippedProtectedReplays = 0;
        int failedDeletes = 0;

        for (ReplaySummary summary : summaries) {
            if (!summary.createdAt().isBefore(cutoff)) {
                continue;
            }
            expiredCandidates++;

            if (summary.protectedFromDeletion()) {
                skippedProtectedReplays++;
                continue;
            }

            try {
                ReplayDeleteResult result = storage.deleteReplay(summary.name()).join();
                if (result == ReplayDeleteResult.DELETED) {
                    deletedReplays++;
                    if (policy.logDeletions()) {
                        logger.info("Retention deleted replay: " + summary.name());
                    }
                } else if (result == ReplayDeleteResult.PROTECTED) {
                    skippedProtectedReplays++;
                } else {
                    failedDeletes++;
                    logger.warning("Retention could not delete replay because it no longer exists: " + summary.name());
                    if (!policy.continueAfterDeleteFailure()) {
                        break;
                    }
                }
            } catch (RuntimeException ex) {
                failedDeletes++;
                logger.log(Level.WARNING, "Retention failed to delete replay: " + summary.name(), ex);
                if (!policy.continueAfterDeleteFailure()) {
                    break;
                }
            }
        }

        if (deletedReplays > 0 && replayCache != null) {
            storage.listReplays().thenAccept(replayCache::setReplays)
                    .exceptionally(ex -> {
                        logger.log(Level.WARNING, "Failed to refresh replay cache after retention run", ex);
                        return null;
                    }).join();
        }

        ReplayRetentionRunResult result = new ReplayRetentionRunResult(
                summaries.size(),
                expiredCandidates,
                deletedReplays,
                skippedProtectedReplays,
                failedDeletes);
        logger.info("Retention scan complete: scanned=" + result.scannedReplays()
                + ", expired=" + result.expiredCandidates()
                + ", deleted=" + result.deletedReplays()
                + ", skippedProtected=" + result.skippedProtectedReplays()
                + ", failures=" + result.failedDeletes());
        return result;
    }

    private void scheduleAsyncPass() {
        if (!scanRunning.compareAndSet(false, true)) {
            return;
        }
        foliaLib.getScheduler().runAsync(task -> {
            try {
                runRetentionPass();
            } finally {
                scanRunning.set(false);
            }
        });
    }
}