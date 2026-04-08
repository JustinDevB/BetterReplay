package me.justindevb.replay.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Utility class for GZIP compression of replay data.
 * GZIP is the standard compression format for Java applications (java.util.zip).
 * Auto-detection allows loading both compressed and uncompressed replays.
 */
public final class ReplayCompressor {

    // GZIP magic bytes: 0x1f 0x8b
    private static final int GZIP_MAGIC_1 = 0x1f;
    private static final int GZIP_MAGIC_2 = 0x8b;

    private ReplayCompressor() {}

    /**
     * GZIP-compress a JSON string and return the raw bytes.
     */
    public static byte[] compress(String json) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    /**
     * Decompress GZIP bytes back to a JSON string.
     */
    public static String decompress(byte[] bytes) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Returns true if the byte array starts with the GZIP magic header.
     * Used for auto-detection when loading replays that may or may not be compressed.
     */
    public static boolean isGzipCompressed(byte[] bytes) {
        return bytes != null
                && bytes.length >= 2
                && (bytes[0] & 0xFF) == GZIP_MAGIC_1
                && (bytes[1] & 0xFF) == GZIP_MAGIC_2;
    }

    /**
     * Decompress bytes to JSON if compressed, otherwise treat as UTF-8 JSON directly.
     * This enables transparent loading of both legacy (uncompressed) and new (compressed) replays.
     */
    public static String decompressIfNeeded(byte[] bytes) throws IOException {
        if (isGzipCompressed(bytes)) {
            return decompress(bytes);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
