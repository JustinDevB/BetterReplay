package me.justindevb.replay.chunk;

import java.util.Objects;

/**
 * Region-grouping key for chunk archive entries.
 */
public record ChunkRegionKey(String worldName, int regionX, int regionZ) {

    public ChunkRegionKey {
        if (Objects.requireNonNull(worldName, "worldName").isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
    }
}