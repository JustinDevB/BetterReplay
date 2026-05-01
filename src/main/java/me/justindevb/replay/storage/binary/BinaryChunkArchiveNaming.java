package me.justindevb.replay.storage.binary;

import java.util.Objects;

/**
 * Canonical archive entry naming for chunk-enabled .br archives.
 */
public final class BinaryChunkArchiveNaming {

    private BinaryChunkArchiveNaming() {
    }

    public static String worldDirectory(String worldName) {
        Objects.requireNonNull(worldName, "worldName");
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }

        byte[] bytes = worldName.getBytes(BinaryReplayFormat.STRING_CHARSET);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte value : bytes) {
            int current = value & 0xFF;
            if (isSafeSegmentByte(current)) {
                encoded.append((char) current);
            } else {
                encoded.append('%').append("%02X".formatted(current));
            }
        }
        return encoded.toString();
    }

    public static String regionEntryName(String worldName, int regionX, int regionZ) {
        return BinaryReplayFormat.RESERVED_CHUNKS_PREFIX
                + worldDirectory(worldName)
                + "/r."
                + regionX
                + "."
                + regionZ
                + BinaryReplayFormat.CHUNK_REGION_FILE_EXTENSION;
    }

    private static boolean isSafeSegmentByte(int value) {
        return value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '.'
                || value == '_'
                || value == '-';
    }
}