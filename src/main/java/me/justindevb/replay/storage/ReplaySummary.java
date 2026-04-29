package me.justindevb.replay.storage;

import java.time.Instant;
import java.util.Objects;

public record ReplaySummary(
        String name,
        Instant createdAt,
        long sizeBytes,
        boolean protectedFromDeletion,
        Instant protectedAt,
        String protectedBy,
        ReplayStorageType storageType
) {

    public ReplaySummary {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(storageType, "storageType");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be non-negative");
        }
        if (protectedFromDeletion) {
            Objects.requireNonNull(protectedAt, "protectedAt");
            if (Objects.requireNonNull(protectedBy, "protectedBy").isBlank()) {
                throw new IllegalArgumentException("protectedBy must not be blank when protectedFromDeletion is true");
            }
        }
    }
}