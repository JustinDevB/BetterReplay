package me.justindevb.replay.chunk;

import me.justindevb.replay.storage.binary.BinaryChunkPayloadFormat;

import java.util.Objects;

/**
 * Captured uncompressed chunk baseline bytes before temp-region persistence.
 */
public record CapturedChunkBaseline(ChunkCoordinate coordinate, byte[] payloadBytes, BinaryChunkPayloadFormat payloadFormat) {

    public CapturedChunkBaseline(ChunkCoordinate coordinate, byte[] payloadBytes) {
        this(coordinate, payloadBytes, BinaryChunkPayloadFormat.BRCS);
    }

    public CapturedChunkBaseline {
        coordinate = Objects.requireNonNull(coordinate, "coordinate");
        payloadBytes = Objects.requireNonNull(payloadBytes, "payloadBytes").clone();
        payloadFormat = Objects.requireNonNull(payloadFormat, "payloadFormat");
        if (payloadBytes.length == 0) {
            throw new IllegalArgumentException("payloadBytes must not be empty");
        }
    }

    @Override
    public byte[] payloadBytes() {
        return payloadBytes.clone();
    }
}