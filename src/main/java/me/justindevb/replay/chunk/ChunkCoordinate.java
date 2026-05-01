package me.justindevb.replay.chunk;

import java.util.Objects;

/**
 * Unique chunk coordinate within a recorded world.
 */
public record ChunkCoordinate(String worldName, int chunkX, int chunkZ) {

    public ChunkCoordinate {
        if (Objects.requireNonNull(worldName, "worldName").isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
    }

    public ChunkRegionKey regionKey() {
        return new ChunkRegionKey(worldName, Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32));
    }

    public int localChunkX() {
        return Math.floorMod(chunkX, 32);
    }

    public int localChunkZ() {
        return Math.floorMod(chunkZ, 32);
    }
}