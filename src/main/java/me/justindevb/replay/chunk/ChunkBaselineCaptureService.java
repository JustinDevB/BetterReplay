package me.justindevb.replay.chunk;

import java.io.IOException;

/**
 * Exports raw chunk baseline bytes for a discovered chunk coordinate.
 */
public interface ChunkBaselineCaptureService {

    CapturedChunkBaseline capture(ChunkCoordinate coordinate) throws IOException;
}