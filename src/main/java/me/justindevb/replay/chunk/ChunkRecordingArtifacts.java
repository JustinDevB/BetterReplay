package me.justindevb.replay.chunk;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Recording-time temp workspace for captured chunk baselines.
 */
public record ChunkRecordingArtifacts(
        Path rootDirectory,
        int capturedChunkCount,
        int regionFileCount
) {

    public static final ChunkRecordingArtifacts NONE = new ChunkRecordingArtifacts(null, 0, 0);

    public ChunkRecordingArtifacts {
        if (rootDirectory != null && rootDirectory.toString().isBlank()) {
            throw new IllegalArgumentException("rootDirectory must not be blank when present");
        }
        if (capturedChunkCount < 0) {
            throw new IllegalArgumentException("capturedChunkCount must not be negative");
        }
        if (regionFileCount < 0) {
            throw new IllegalArgumentException("regionFileCount must not be negative");
        }
        if (rootDirectory == null && (capturedChunkCount != 0 || regionFileCount != 0)) {
            throw new IllegalArgumentException("captured counts require a rootDirectory");
        }
    }

    public boolean isPresent() {
        return rootDirectory != null;
    }
}