package me.justindevb.replay.chunk;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Produces the square chunk-interest window around tracked players.
 */
public final class RadiusChunkInterestTracker implements ChunkInterestTracker {

    @Override
    public Set<ChunkCoordinate> discoverChunks(Collection<ChunkCoordinate> trackedPlayerChunks, int radius) {
        Objects.requireNonNull(trackedPlayerChunks, "trackedPlayerChunks");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }

        Set<ChunkCoordinate> discovered = new LinkedHashSet<>();
        for (ChunkCoordinate playerChunk : trackedPlayerChunks) {
            for (int deltaX = -radius; deltaX <= radius; deltaX++) {
                for (int deltaZ = -radius; deltaZ <= radius; deltaZ++) {
                    discovered.add(new ChunkCoordinate(
                            playerChunk.worldName(),
                            playerChunk.chunkX() + deltaX,
                            playerChunk.chunkZ() + deltaZ));
                }
            }
        }
        return discovered;
    }
}