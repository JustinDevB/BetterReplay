package me.justindevb.replay.chunk;

import java.util.Objects;

/**
 * Captured uncompressed chunk baseline bytes before temp-region persistence.
 */
public record CapturedChunkBaseline(ChunkCoordinate coordinate, byte[] payloadBytes) {

    public CapturedChunkBaseline {
        coordinate = Objects.requireNonNull(coordinate, "coordinate");
        payloadBytes = Objects.requireNonNull(payloadBytes, "payloadBytes").clone();
        if (payloadBytes.length == 0) {
            throw new IllegalArgumentException("payloadBytes must not be empty");
        }
    }

    @Override
    public byte[] payloadBytes() {
        return payloadBytes.clone();
    }
}