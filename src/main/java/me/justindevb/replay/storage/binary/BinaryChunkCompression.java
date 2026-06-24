package me.justindevb.replay.storage.binary;

import java.io.IOException;

/**
 * Frozen per-chunk payload codecs for chunk baseline storage.
 */
public enum BinaryChunkCompression {
    LZ4_FRAME(1, BinaryReplayPayloadCompression.LZ4_FRAME),
    ZSTD(2, BinaryReplayPayloadCompression.ZSTD);

    public static final BinaryChunkCompression DEFAULT = ZSTD;

    private final int codecId;
    private final BinaryReplayPayloadCompression payloadCompression;

    BinaryChunkCompression(int codecId, BinaryReplayPayloadCompression payloadCompression) {
        this.codecId = codecId;
        this.payloadCompression = payloadCompression;
    }

    public int codecId() {
        return codecId;
    }

    public byte[] compress(byte[] payload) throws IOException {
        return payloadCompression.compress(payload);
    }

    public byte[] decompress(byte[] compressedPayload) throws IOException {
        return payloadCompression.decompress(compressedPayload);
    }

    public static BinaryChunkCompression fromCodecId(int codecId) throws IOException {
        for (BinaryChunkCompression compression : values()) {
            if (compression.codecId == codecId) {
                return compression;
            }
        }
        throw new IOException("Unsupported chunk payload codec id: " + codecId);
    }
}
