package me.justindevb.replay.retention;

public record ReplayRetentionRunResult(
        int scannedReplays,
        int expiredCandidates,
        int deletedReplays,
        int skippedProtectedReplays,
        int failedDeletes
) {
}