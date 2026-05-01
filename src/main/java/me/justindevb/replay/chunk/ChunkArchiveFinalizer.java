package me.justindevb.replay.chunk;

import java.io.IOException;

/**
 * Converts recording-time temp chunk artifacts into finalized archive entries.
 */
public interface ChunkArchiveFinalizer {

    ReplayChunkData finalizeArtifacts(ChunkRecordingArtifacts artifacts) throws IOException;
}