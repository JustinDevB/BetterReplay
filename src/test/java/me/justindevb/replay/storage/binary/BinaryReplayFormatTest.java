package me.justindevb.replay.storage.binary;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BinaryReplayFormatTest {

    private final Gson gson = new Gson();

    @Test
    void exposesFrozenArchiveAndPayloadConstants() {
        assertEquals(".br", BinaryReplayFormat.FILE_EXTENSION);
        assertEquals("manifest.json", BinaryReplayFormat.MANIFEST_ENTRY_NAME);
        assertEquals("replay.bin", BinaryReplayFormat.REPLAY_ENTRY_NAME);
        assertEquals("chunks/", BinaryReplayFormat.RESERVED_CHUNKS_PREFIX);
        assertEquals("meta/", BinaryReplayFormat.RESERVED_META_PREFIX);
        assertEquals(".brregion", BinaryReplayFormat.CHUNK_REGION_FILE_EXTENSION);
        assertEquals(1, BinaryReplayFormat.FORMAT_VERSION);
        assertEquals("CRC32C", BinaryReplayFormat.PAYLOAD_CHECKSUM_ALGORITHM);
        assertArrayEquals(new byte[] {'B', 'R', 'A', 'L'}, BinaryReplayFormat.appendLogMagicBytes());
        assertEquals(16, BinaryReplayFormat.APPEND_LOG_HEADER_SIZE);
        assertArrayEquals(new byte[] {'B', 'R', 'P', 'L'}, BinaryReplayFormat.payloadMagicBytes());
        assertEquals("4252504c", BinaryReplayFormat.payloadMagicHex());
        assertEquals(8, BinaryReplayFormat.PAYLOAD_HEADER_SIZE);
        assertArrayEquals(new byte[] {'B', 'R', 'R', 'G'}, BinaryReplayFormat.chunkRegionMagicBytes());
        assertEquals(16, BinaryReplayFormat.CHUNK_REGION_HEADER_SIZE);
        assertEquals(16, BinaryReplayFormat.CHUNK_REGION_INDEX_ENTRY_BYTES);
        assertArrayEquals(new byte[] {'B', 'R', 'T', 'C'}, BinaryReplayFormat.chunkTempRegionMagicBytes());
        assertEquals(8, BinaryReplayFormat.CHUNK_TEMP_REGION_HEADER_SIZE);
        assertEquals(16, BinaryReplayFormat.CHUNK_TEMP_REGION_RECORD_HEADER_BYTES);
    }

    @Test
    void exposesEncodingDefaults() {
        assertEquals(ByteOrder.LITTLE_ENDIAN, BinaryReplayFormat.PRIMITIVE_BYTE_ORDER);
        assertEquals(5, BinaryReplayFormat.VAR_INT_MAX_BYTES);
        assertEquals(10, BinaryReplayFormat.VAR_LONG_MAX_BYTES);
        assertEquals(0, BinaryReplayFormat.STRING_TABLE_INDEX_BASE);
        assertEquals(50, BinaryReplayFormat.TICK_INDEX_INTERVAL);
        assertEquals(12, BinaryReplayFormat.TICK_INDEX_ENTRY_BYTES);
        assertEquals(Integer.BYTES, BinaryReplayFormat.APPEND_LOG_CRC_BYTES);
        assertTrue(BinaryReplayFormat.isValidBooleanEncoding(BinaryReplayFormat.BOOLEAN_FALSE));
        assertTrue(BinaryReplayFormat.isValidBooleanEncoding(BinaryReplayFormat.BOOLEAN_TRUE));
        assertFalse(BinaryReplayFormat.isValidBooleanEncoding(2));
    }

    @Test
    void recognizesReservedArchivePrefixes() {
        assertTrue(BinaryReplayFormat.isReservedArchivePrefix("chunks/segment-0.bin"));
        assertTrue(BinaryReplayFormat.isReservedArchivePrefix("meta/debug.json"));
        assertFalse(BinaryReplayFormat.isReservedArchivePrefix("manifest.json"));
    }

    @Test
    void usesUnsignedLeb128VarIntEncoding() {
        assertArrayEquals(new byte[] {0x05}, BinaryEncoding.encodeVarInt(5));
        assertArrayEquals(new byte[] {(byte) 0xC8, 0x01}, BinaryEncoding.encodeVarInt(200));
        assertArrayEquals(new byte[] {(byte) 0xA0, (byte) 0x9C, 0x01}, BinaryEncoding.encodeVarInt(20_000));

        assertEquals(5, BinaryEncoding.decodeVarInt(new byte[] {0x05}));
        assertEquals(200, BinaryEncoding.decodeVarInt(new byte[] {(byte) 0xC8, 0x01}));
        assertEquals(20_000, BinaryEncoding.decodeVarInt(new byte[] {(byte) 0xA0, (byte) 0x9C, 0x01}));
        assertThrows(IllegalArgumentException.class, () -> BinaryEncoding.encodeVarInt(-1));
    }

    @Test
    void usesUtf8LengthPrefixedStrings() {
        byte[] encoded = BinaryEncoding.encodeLengthPrefixedString("Steve");

        assertArrayEquals(
                new byte[] {0x05, 'S', 't', 'e', 'v', 'e'},
                encoded);
        assertEquals(StandardCharsets.UTF_8, BinaryReplayFormat.STRING_CHARSET);

        BinaryEncoding.DecodedString decoded = BinaryEncoding.decodeLengthPrefixedString(encoded);
        assertEquals("Steve", decoded.value());
        assertEquals(1, decoded.payloadOffset());
    }

    @Test
    void definesFixedWidthTickIndexEntries() {
        BinaryTickIndexEntry entry = new BinaryTickIndexEntry(100, 36_415L);

        assertEquals(100, entry.tick());
        assertEquals(36_415L, entry.byteOffset());
        assertThrows(IllegalArgumentException.class, () -> new BinaryTickIndexEntry(25, 10L));
        assertThrows(IllegalArgumentException.class, () -> new BinaryTickIndexEntry(50, -1L));
    }

    @Test
    void createsV1ManifestWithCurrentDefaults() {
        BinaryReplayManifest manifest = BinaryReplayManifest.createV1("1.4.0", "1.4.0", 1_700_000_000_000L, "7d8f8f2b");

        assertEquals(BinaryReplayFormat.FORMAT_VERSION, manifest.formatVersion());
        assertEquals(BinaryReplayFormat.PAYLOAD_CHECKSUM_ALGORITHM, manifest.payloadChecksumAlgorithm());
        assertEquals("1.4.0", manifest.recordedWithVersion());
        assertEquals("1.4.0", manifest.minimumViewerVersion());
        assertEquals(1_700_000_000_000L, manifest.recordingStartedAtEpochMillis());
        assertEquals("7d8f8f2b", manifest.payloadChecksum());
        assertEquals(BinaryReplayChunkMetadata.none(), manifest.chunkMetadata());
    }

    @Test
    void createsChunkEnabledManifestWithExplicitMetadata() {
        BinaryReplayManifest manifest = BinaryReplayManifest.createV1(
                "1.4.0",
                "1.4.0",
                1_700_000_000_000L,
                "7d8f8f2b",
                BinaryReplayChunkMetadata.present(2, 17, "00ff11aa"));

        assertTrue(manifest.hasChunkData());
        assertEquals(2, manifest.chunkRegionEntryCount());
        assertEquals(17, manifest.chunkEntryCount());
        assertEquals("00ff11aa", manifest.chunkCoordinateHash());
        assertEquals(BinaryChunkPayloadFormat.BRCS.manifestValue(), manifest.chunkPayloadFormat());
        assertEquals(1, manifest.chunkPayloadVersion());
    }

    @Test
    void createsChunkEnabledManifestWithPacketFriendlyMetadata() {
        BinaryReplayManifest manifest = BinaryReplayManifest.createV1(
                "1.4.0",
                "1.4.0",
                1_700_000_000_000L,
                "7d8f8f2b",
                BinaryReplayChunkMetadata.present(2, 17, "00ff11aa", BinaryChunkPayloadFormat.BRCP));

        assertEquals(BinaryChunkPayloadFormat.BRCP.manifestValue(), manifest.chunkPayloadFormat());
        assertEquals(1, manifest.chunkPayloadVersion());
        assertEquals(BinaryChunkPayloadFormat.BRCP, manifest.chunkMetadata().payloadFormat());
    }

    @Test
    void legacyChunkManifestJsonDefaultsToBrcsV1() {
        BinaryReplayManifest manifest = gson.fromJson("""
                {
                  "formatVersion": 1,
                  "recordedWithVersion": "1.4.0",
                  "minimumViewerVersion": "1.4.0",
                  "recordingStartedAtEpochMillis": 1700000000000,
                  "payloadChecksum": "7d8f8f2b",
                  "payloadChecksumAlgorithm": "CRC32C",
                  "hasChunkData": true,
                  "chunkRegionEntryCount": 2,
                  "chunkEntryCount": 17,
                  "chunkCoordinateHash": "00ff11aa"
                }
                """, BinaryReplayManifest.class);

        assertEquals(BinaryChunkPayloadFormat.BRCS.manifestValue(), manifest.chunkPayloadFormat());
        assertEquals(1, manifest.chunkPayloadVersion());
        assertEquals(BinaryChunkPayloadFormat.BRCS, manifest.chunkMetadata().payloadFormat());
    }

    @Test
    void rejectsInvalidManifestFields() {
        assertThrows(IllegalArgumentException.class,
            () -> new BinaryReplayManifest(0, "1.4.0", "1.4.0", 1_700_000_000_000L, "7d8f8f2b", "CRC32C", false, 0, 0, null, null, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new BinaryReplayManifest(1, " ", "1.4.0", 1_700_000_000_000L, "7d8f8f2b", "CRC32C", false, 0, 0, null, null, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new BinaryReplayManifest(1, "1.4.0", "1.4.0", 0, "7d8f8f2b", "CRC32C", false, 0, 0, null, null, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new BinaryReplayManifest(1, "1.4.0", "1.4.0", 1_700_000_000_000L, "NOT_HEX", "CRC32C", false, 0, 0, null, null, 0));
        assertThrows(IllegalArgumentException.class,
            () -> BinaryReplayChunkMetadata.present(0, 1, null));
        assertThrows(IllegalArgumentException.class,
            () -> new BinaryReplayManifest(1, "1.4.0", "1.4.0", 1_700_000_000_000L, "7d8f8f2b", "CRC32C", false, 1, 1, null, null, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new BinaryReplayManifest(1, "1.4.0", "1.4.0", 1_700_000_000_000L, "7d8f8f2b", "CRC32C", true, 1, 1, null, "BRCP", 2));
    }

    @Test
    void freezesDefaultChunkCorruptionPolicy() {
        assertEquals(BinaryChunkFailurePolicy.SOFT_FAIL_ENTITY_ONLY, BinaryChunkFailurePolicy.defaultPolicy());
    }
}