package me.justindevb.replay.chunk;

import me.justindevb.replay.storage.binary.BinaryChunkPayloadFormat;

import java.nio.file.Path;

/**
 * Recording-time temp workspace for captured chunk baselines.
 */
public record ChunkRecordingArtifacts(
        Path rootDirectory,
        int capturedChunkCount,
        int regionFileCount,
        BinaryChunkPayloadFormat chunkPayloadFormat
) {

    public static final ChunkRecordingArtifacts NONE = new ChunkRecordingArtifacts(null, 0, 0, null);

    public ChunkRecordingArtifacts(Path rootDirectory, int capturedChunkCount, int regionFileCount) {
        this(rootDirectory, capturedChunkCount, regionFileCount, rootDirectory == null ? null : BinaryChunkPayloadFormat.BRCS);
    }

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
        if (rootDirectory == null && chunkPayloadFormat != null) {
            throw new IllegalArgumentException("chunkPayloadFormat requires a rootDirectory");
        }
        if (rootDirectory != null && chunkPayloadFormat == null) {
            throw new IllegalArgumentException("chunkPayloadFormat must be present when rootDirectory is present");
        }
    }

    public boolean isPresent() {
        return rootDirectory != null;
    }
}