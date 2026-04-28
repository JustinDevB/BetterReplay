package me.justindevb.replay.storage.binary;

import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Central binary replay format constants for BetterReplay's v1 .br archives.
 */
public final class BinaryReplayFormat {

    public static final String FILE_EXTENSION = ".br";

    public static final String MANIFEST_ENTRY_NAME = "manifest.json";
    public static final String REPLAY_ENTRY_NAME = "replay.bin";
    public static final String RESERVED_CHUNKS_PREFIX = "chunks/";
    public static final String RESERVED_META_PREFIX = "meta/";

    public static final int FORMAT_VERSION = 1;
    public static final String PAYLOAD_CHECKSUM_ALGORITHM = "CRC32C";

        public static final byte[] APPEND_LOG_MAGIC = new byte[] {'B', 'R', 'A', 'L'};
        public static final int APPEND_LOG_HEADER_VERSION = 1;
        public static final int APPEND_LOG_HEADER_FLAGS_NONE = 0;
        public static final int APPEND_LOG_HEADER_SIZE = APPEND_LOG_MAGIC.length
            + Byte.BYTES
            + Byte.BYTES
            + Short.BYTES
            + Long.BYTES;

    public static final byte[] PAYLOAD_MAGIC = new byte[] {'B', 'R', 'P', 'L'};
    public static final int PAYLOAD_HEADER_VERSION_BYTES = 1;
    public static final int PAYLOAD_HEADER_FLAGS_BYTES = 1;
    public static final int PAYLOAD_HEADER_RESERVED_BYTES = 2;
    public static final int PAYLOAD_HEADER_SIZE = PAYLOAD_MAGIC.length
            + PAYLOAD_HEADER_VERSION_BYTES
            + PAYLOAD_HEADER_FLAGS_BYTES
            + PAYLOAD_HEADER_RESERVED_BYTES;
    public static final int PAYLOAD_FLAGS_NONE = 0;
    public static final byte[] INDEX_SECTION_MAGIC = new byte[] {'B', 'R', 'I', 'X'};
    public static final int INDEX_SECTION_FOOTER_BYTES = Long.BYTES;

    public static final ByteOrder PRIMITIVE_BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;
    public static final Charset STRING_CHARSET = StandardCharsets.UTF_8;
    public static final int BOOLEAN_FALSE = 0x00;
    public static final int BOOLEAN_TRUE = 0x01;

    public static final int VAR_INT_MAX_BYTES = 5;
    public static final int VAR_LONG_MAX_BYTES = 10;
    public static final int STRING_TABLE_INDEX_BASE = 0;

    public static final int APPEND_LOG_CRC_BYTES = Integer.BYTES;
    public static final int TICK_INDEX_INTERVAL = 50;
    public static final int TICK_INDEX_ENTRY_BYTES = Integer.BYTES + Long.BYTES;

    private BinaryReplayFormat() {
    }

    public static boolean isReservedArchivePrefix(String entryName) {
        return entryName.startsWith(RESERVED_CHUNKS_PREFIX) || entryName.startsWith(RESERVED_META_PREFIX);
    }

    public static boolean isValidBooleanEncoding(int value) {
        return value == BOOLEAN_FALSE || value == BOOLEAN_TRUE;
    }

    public static byte[] payloadMagicBytes() {
        return PAYLOAD_MAGIC.clone();
    }

    public static byte[] appendLogMagicBytes() {
        return APPEND_LOG_MAGIC.clone();
    }

    public static byte[] indexSectionMagicBytes() {
        return INDEX_SECTION_MAGIC.clone();
    }

    public static String payloadMagicHex() {
        return HexFormat.of().formatHex(PAYLOAD_MAGIC);
    }
}