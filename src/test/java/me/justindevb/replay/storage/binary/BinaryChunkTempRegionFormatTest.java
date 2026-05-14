package me.justindevb.replay.storage.binary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinaryChunkTempRegionFormatTest {

    private final BinaryChunkTempRegionFormat format = new BinaryChunkTempRegionFormat();

    @Test
    void freezesTempHeaderBytes() throws Exception {
        byte[] header = format.headerBytes();

        assertArrayEquals(new byte[] {'B', 'R', 'T', 'C', 0x01, 0x00, 0x00, 0x00}, header);
        format.validateHeader(header);
    }

    @Test
    void encodesAndDecodesTempAppendRecords() throws Exception {
        BinaryChunkTempRegionAppendRecord record = new BinaryChunkTempRegionAppendRecord(
                7,
                9,
                128,
                BinaryChunkCompression.LZ4_FRAME,
                new byte[] {0x10, 0x20, 0x30});

        byte[] encoded = format.encodeRecord(record);
        BinaryChunkTempRegionFormat.DecodedAppendRecord decoded = format.decodeRecord(encoded, 0);

        assertEquals(7, encoded[0] & 0xFF);
        assertEquals(9, encoded[1] & 0xFF);
        assertEquals(BinaryChunkCompression.LZ4_FRAME.codecId(), encoded[2] & 0xFF);
        assertEquals(128, littleEndianInt(encoded, 4));
        assertEquals(3, littleEndianInt(encoded, 8));
        assertEquals(encoded.length, decoded.nextOffset());
        assertEquals(record.localChunkX(), decoded.record().localChunkX());
        assertEquals(record.localChunkZ(), decoded.record().localChunkZ());
        assertEquals(record.uncompressedLength(), decoded.record().uncompressedLength());
        assertArrayEquals(record.compressedPayload(), decoded.record().compressedPayload());
    }

    @Test
    void rejectsCorruptedRecordChecksums() {
        BinaryChunkTempRegionAppendRecord record = new BinaryChunkTempRegionAppendRecord(
                1,
                1,
                16,
                BinaryChunkCompression.LZ4_FRAME,
                new byte[] {0x01, 0x02});
        byte[] encoded = format.encodeRecord(record);
        encoded[encoded.length - 1] ^= 0x01;

        assertThrows(IOException.class, () -> format.decodeRecord(encoded, 0));
    }

    @Test
    void rejectsTruncatedHeaders() {
        assertThrows(IOException.class, () -> format.validateHeader(Arrays.copyOf(format.headerBytes(), 4)));
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, Integer.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .getInt();
    }
}