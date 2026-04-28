package me.justindevb.replay.storage;

public record ReplayInspection(
        String replayName,
        ReplayFormat format,
        int recordCount,
        int startTick,
        int endTick,
        int durationTicks,
        double durationSeconds,
        long storedBytes,
        long compressedPayloadBytes,
        long decompressedPayloadBytes,
        Long recordingStartedAtEpochMillis,
        String recordedWithVersion,
        String minimumViewerVersion,
        int uniqueActorCount,
        int uniqueWorldCount,
        boolean indexedPayload,
        int seekCheckpointCount
) {
}