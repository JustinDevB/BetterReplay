package me.justindevb.replay.storage.binary;

import java.io.IOException;

/**
 * Frozen per-chunk payload codecs for chunk baseline storage.
 */
public enum BinaryChunkCompression {
    LZ4_FRAME(1);

    private final int codecId;

    BinaryChunkCompression(int codecId) {
        this.codecId = codecId;
    }

    public int codecId() {
        return codecId;
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