package me.justindevb.replay.chunk;

import java.util.Collection;
import java.util.Set;

/**
 * Computes the union of chunks that should be available for capture or playback.
 */
public interface ChunkInterestTracker {

    Set<ChunkCoordinate> discoverChunks(Collection<ChunkCoordinate> trackedPlayerChunks, int radius);
}