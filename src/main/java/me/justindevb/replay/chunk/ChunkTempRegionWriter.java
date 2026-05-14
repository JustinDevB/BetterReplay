package me.justindevb.replay.chunk;

import java.io.IOException;

/**
 * Persists captured chunk baselines into append-friendly temp-region files.
 */
public interface ChunkTempRegionWriter extends AutoCloseable {

    void append(CapturedChunkBaseline baseline) throws IOException;

    ChunkRecordingArtifacts snapshotArtifacts();

    @Override
    void close() throws IOException;
}