package me.justindevb.replay.storage.binary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinaryChunkRegionCodecTest {

    private final BinaryChunkRegionCodec codec = new BinaryChunkRegionCodec();

    @Test
    void encodesDeterministicRegionHeaderIndexAndPayloadLayout() throws Exception {
        byte[] regionBytes = codec.encode(List.of(
                new BinaryChunkRegionEntry(3, 7, 20, BinaryChunkCompression.LZ4_FRAME, new byte[] {0x44, 0x55, 0x66}),
                new BinaryChunkRegionEntry(1, 2, 11, BinaryChunkCompression.LZ4_FRAME, new byte[] {0x11, 0x22, 0x33})
        ));

        assertArrayEquals(BinaryReplayFormat.CHUNK_REGION_MAGIC, slice(regionBytes, 0, 4));
        assertEquals(BinaryReplayFormat.CHUNK_REGION_VERSION, regionBytes[4] & 0xFF);
        assertEquals(0, regionBytes[5] & 0xFF);
        assertEquals(2, littleEndianInt(regionBytes, 8));
        assertEquals(48, littleEndianInt(regionBytes, 12));

        assertEquals(1, regionBytes[16] & 0xFF);
        assertEquals(2, regionBytes[17] & 0xFF);
        assertEquals(BinaryChunkCompression.LZ4_FRAME.codecId(), regionBytes[18] & 0xFF);
        assertEquals(0, littleEndianInt(regionBytes, 20));
        assertEquals(3, littleEndianInt(regionBytes, 24));
        assertEquals(11, littleEndianInt(regionBytes, 28));

        assertEquals(3, regionBytes[32] & 0xFF);
        assertEquals(7, regionBytes[33] & 0xFF);
        assertEquals(BinaryChunkCompression.LZ4_FRAME.codecId(), regionBytes[34] & 0xFF);
        assertEquals(3, littleEndianInt(regionBytes, 36));
        assertEquals(3, littleEndianInt(regionBytes, 40));
        assertEquals(20, littleEndianInt(regionBytes, 44));

        assertArrayEquals(new byte[] {0x11, 0x22, 0x33, 0x44, 0x55, 0x66}, slice(regionBytes, 48, 54));
    }

    @Test
    void decodesRegionEntriesAndIndexRows() throws Exception {
        BinaryChunkRegionCodec.DecodedBinaryChunkRegion decoded = codec.decode(codec.encode(List.of(
                new BinaryChunkRegionEntry(0, 0, 32, BinaryChunkCompression.LZ4_FRAME, new byte[] {0x01}),
                new BinaryChunkRegionEntry(31, 31, 48, BinaryChunkCompression.LZ4_FRAME, new byte[] {0x02, 0x03})
        )));

        assertEquals(2, decoded.indexEntries().size());
        assertEquals(new BinaryChunkRegionIndexEntry(0, 0, 0, 1, 32, BinaryChunkCompression.LZ4_FRAME), decoded.indexEntries().get(0));
        assertEquals(new BinaryChunkRegionIndexEntry(31, 31, 1, 2, 48, BinaryChunkCompression.LZ4_FRAME), decoded.indexEntries().get(1));
        assertArrayEquals(new byte[] {0x01}, decoded.entries().get(0).compressedPayload());
        assertArrayEquals(new byte[] {0x02, 0x03}, decoded.entries().get(1).compressedPayload());
    }

    @Test
    void rejectsDuplicateChunkCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> codec.encode(List.of(
                new BinaryChunkRegionEntry(4, 4, 8, BinaryChunkCompression.LZ4_FRAME, new byte[] {0x01}),
                new BinaryChunkRegionEntry(4, 4, 9, BinaryChunkCompression.LZ4_FRAME, new byte[] {0x02})
        )));
    }

    @Test
    void rejectsOverlappingPayloadRanges() {
        byte[] regionBytes = codec.encode(List.of(
                new BinaryChunkRegionEntry(1, 1, 8, BinaryChunkCompression.LZ4_FRAME, new byte[] {0x01}),
                new BinaryChunkRegionEntry(2, 2, 8, BinaryChunkCompression.LZ4_FRAME, new byte[] {0x02})
        ));
        ByteBuffer.wrap(regionBytes, 36, Integer.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putInt(0);

        assertThrows(IOException.class, () -> codec.decode(regionBytes));
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, Integer.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .getInt();
    }

    private static byte[] slice(byte[] bytes, int start, int end) {
        byte[] slice = new byte[end - start];
        System.arraycopy(bytes, start, slice, 0, slice.length);
        return slice;
    }
}