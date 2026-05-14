package me.justindevb.replay.storage.binary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * Exact temp region append-log framing for chunk baseline capture.
 */
public final class BinaryChunkTempRegionFormat {

    public byte[] headerBytes() {
        return new byte[] {
                BinaryReplayFormat.CHUNK_TEMP_REGION_MAGIC[0],
                BinaryReplayFormat.CHUNK_TEMP_REGION_MAGIC[1],
                BinaryReplayFormat.CHUNK_TEMP_REGION_MAGIC[2],
                BinaryReplayFormat.CHUNK_TEMP_REGION_MAGIC[3],
                (byte) BinaryReplayFormat.CHUNK_TEMP_REGION_VERSION,
                (byte) BinaryReplayFormat.CHUNK_TEMP_REGION_FLAGS_NONE,
                0x00,
                0x00
        };
    }

    public void validateHeader(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < BinaryReplayFormat.CHUNK_TEMP_REGION_HEADER_SIZE) {
            throw new IOException("Chunk temp region header is too short");
        }
        if (!Arrays.equals(
                Arrays.copyOfRange(bytes, 0, BinaryReplayFormat.CHUNK_TEMP_REGION_MAGIC.length),
                BinaryReplayFormat.CHUNK_TEMP_REGION_MAGIC)) {
            throw new IOException("Invalid chunk temp region header magic");
        }
        if ((bytes[4] & 0xFF) != BinaryReplayFormat.CHUNK_TEMP_REGION_VERSION) {
            throw new IOException("Unsupported chunk temp region header version: " + (bytes[4] & 0xFF));
        }
        if ((bytes[5] & 0xFF) != BinaryReplayFormat.CHUNK_TEMP_REGION_FLAGS_NONE) {
            throw new IOException("Unsupported chunk temp region header flags: " + (bytes[5] & 0xFF));
        }
        if (bytes[6] != 0 || bytes[7] != 0) {
            throw new IOException("Chunk temp region header reserved bytes must be zero");
        }
    }

    public byte[] encodeRecord(BinaryChunkTempRegionAppendRecord record) {
        Objects.requireNonNull(record, "record");
        byte[] compressedPayload = record.compressedPayload();
        ByteArrayOutputStream out = new ByteArrayOutputStream(BinaryReplayFormat.CHUNK_TEMP_REGION_RECORD_HEADER_BYTES + compressedPayload.length);
        out.write(record.localChunkX());
        out.write(record.localChunkZ());
        out.write(record.compression().codecId());
        out.write(0x00);
        out.writeBytes(littleEndianInt(record.uncompressedLength()));
        out.writeBytes(littleEndianInt(compressedPayload.length));
        out.writeBytes(littleEndianInt(crc32c(compressedPayload)));
        out.writeBytes(compressedPayload);
        return out.toByteArray();
    }

    public DecodedAppendRecord decodeRecord(byte[] bytes, int offset) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (offset < 0 || offset > bytes.length) {
            throw new IllegalArgumentException("offset must be within the byte array");
        }
        if (bytes.length - offset < BinaryReplayFormat.CHUNK_TEMP_REGION_RECORD_HEADER_BYTES) {
            throw new IOException("Chunk temp region record header is truncated");
        }

        int localChunkX = bytes[offset] & 0xFF;
        int localChunkZ = bytes[offset + 1] & 0xFF;
        BinaryChunkCompression compression = BinaryChunkCompression.fromCodecId(bytes[offset + 2] & 0xFF);
        if (bytes[offset + 3] != 0) {
            throw new IOException("Chunk temp region record flags must be zero in v1");
        }
        ByteBuffer header = ByteBuffer.wrap(bytes, offset + 4, BinaryReplayFormat.CHUNK_TEMP_REGION_RECORD_HEADER_BYTES - 4)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER);
        int uncompressedLength = header.getInt();
        int compressedLength = header.getInt();
        int expectedChecksum = header.getInt();
        if (uncompressedLength <= 0) {
            throw new IOException("Chunk temp region record uncompressed length must be positive");
        }
        if (compressedLength <= 0) {
            throw new IOException("Chunk temp region record compressed length must be positive");
        }

        int payloadStart = offset + BinaryReplayFormat.CHUNK_TEMP_REGION_RECORD_HEADER_BYTES;
        int payloadEnd = payloadStart + compressedLength;
        if (payloadEnd < payloadStart || payloadEnd > bytes.length) {
            throw new IOException("Chunk temp region record payload exceeds available bytes");
        }
        byte[] compressedPayload = Arrays.copyOfRange(bytes, payloadStart, payloadEnd);
        if (crc32c(compressedPayload) != expectedChecksum) {
            throw new IOException("Chunk temp region record checksum mismatch");
        }

        return new DecodedAppendRecord(
                new BinaryChunkTempRegionAppendRecord(localChunkX, localChunkZ, uncompressedLength, compression, compressedPayload),
                payloadEnd);
    }

    private static int crc32c(byte[] bytes) {
        CRC32C crc32c = new CRC32C();
        crc32c.update(bytes, 0, bytes.length);
        return (int) crc32c.getValue();
    }

    private static byte[] littleEndianInt(int value) {
        return ByteBuffer.allocate(Integer.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putInt(value)
                .array();
    }

    public record DecodedAppendRecord(BinaryChunkTempRegionAppendRecord record, int nextOffset) {
    }
}