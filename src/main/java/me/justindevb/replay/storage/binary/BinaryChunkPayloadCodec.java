package me.justindevb.replay.storage.binary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Encodes palette-based chunk baselines used by chunk capture and playback.
 */
public final class BinaryChunkPayloadCodec {

    private static final byte[] MAGIC = new byte[] { 'B', 'R', 'C', 'S' };
    private static final int VERSION = 1;
    private static final int FIXED_HEADER_BYTES = 16;

    public byte[] encode(int minY, int height, List<String> palette, short[] stateIndexes) {
        Objects.requireNonNull(palette, "palette");
        Objects.requireNonNull(stateIndexes, "stateIndexes");
        if (height <= 0) {
            throw new IllegalArgumentException("height must be positive");
        }
        int expectedBlockCount = 16 * 16 * height;
        if (stateIndexes.length != expectedBlockCount) {
            throw new IllegalArgumentException("stateIndexes length must equal 16*16*height");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        out.write(VERSION);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.writeBytes(littleEndianInt(minY));
        out.writeBytes(littleEndianInt(height));
        out.writeBytes(BinaryEncoding.encodeVarInt(palette.size()));
        for (String state : palette) {
            out.writeBytes(BinaryEncoding.encodeLengthPrefixedString(state));
        }
        for (short stateIndex : stateIndexes) {
            out.writeBytes(littleEndianShort(stateIndex & 0xFFFF));
        }
        return out.toByteArray();
    }

    public DecodedChunkPayload decode(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < FIXED_HEADER_BYTES) {
            throw new IOException("Chunk payload is too short");
        }
        if (!Arrays.equals(Arrays.copyOfRange(bytes, 0, MAGIC.length), MAGIC)) {
            throw new IOException("Invalid chunk payload magic");
        }
        if ((bytes[4] & 0xFF) != VERSION) {
            throw new IOException("Unsupported chunk payload version: " + (bytes[4] & 0xFF));
        }
        if (bytes[5] != 0 || bytes[6] != 0 || bytes[7] != 0) {
            throw new IOException("Chunk payload reserved bytes must be zero");
        }

        ByteBuffer header = ByteBuffer.wrap(bytes, 8, 8).order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER);
        int minY = header.getInt();
        int height = header.getInt();
        if (height <= 0) {
            throw new IOException("Chunk payload height must be positive");
        }

        Cursor cursor = new Cursor(bytes, FIXED_HEADER_BYTES);
        int paletteSize = cursor.readVarInt();
        List<String> palette = new ArrayList<>(paletteSize);
        for (int index = 0; index < paletteSize; index++) {
            palette.add(cursor.readString());
        }

        int expectedBlockCount = 16 * 16 * height;
        if (cursor.remaining() != expectedBlockCount * Short.BYTES) {
            throw new IOException("Chunk payload block state length mismatch");
        }
        short[] stateIndexes = new short[expectedBlockCount];
        for (int index = 0; index < expectedBlockCount; index++) {
            stateIndexes[index] = (short) cursor.readUnsignedShort();
        }

        return new DecodedChunkPayload(minY, height, List.copyOf(palette), stateIndexes);
    }

    private static byte[] littleEndianInt(int value) {
        return ByteBuffer.allocate(Integer.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putInt(value)
                .array();
    }

    private static byte[] littleEndianShort(int value) {
        return ByteBuffer.allocate(Short.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putShort((short) value)
                .array();
    }

    private static final class Cursor {
        private final byte[] bytes;
        private int offset;

        private Cursor(byte[] bytes, int offset) {
            this.bytes = bytes;
            this.offset = offset;
        }

        private int remaining() {
            return bytes.length - offset;
        }

        private int readVarInt() throws IOException {
            int value = 0;
            int shift = 0;
            int consumed = 0;
            while (offset < bytes.length) {
                int current = bytes[offset++] & 0xFF;
                value |= (current & 0x7F) << shift;
                consumed++;
                if ((current & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (consumed == BinaryReplayFormat.VAR_INT_MAX_BYTES) {
                    throw new IOException("Chunk payload VarInt exceeds supported size");
                }
            }
            throw new IOException("Chunk payload VarInt was truncated");
        }

        private String readString() throws IOException {
            int length = readVarInt();
            if (length < 0 || length > remaining()) {
                throw new IOException("Chunk payload string exceeds available bytes");
            }
            String value = new String(bytes, offset, length, java.nio.charset.StandardCharsets.UTF_8);
            offset += length;
            return value;
        }

        private int readUnsignedShort() throws IOException {
            if (remaining() < Short.BYTES) {
                throw new IOException("Chunk payload short value was truncated");
            }
            int value = ByteBuffer.wrap(bytes, offset, Short.BYTES)
                    .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                    .getShort() & 0xFFFF;
            offset += Short.BYTES;
            return value;
        }
    }

    public record DecodedChunkPayload(int minY, int height, List<String> palette, short[] stateIndexes) {

        public DecodedChunkPayload {
            palette = List.copyOf(palette);
            stateIndexes = stateIndexes.clone();
        }

        @Override
        public short[] stateIndexes() {
            return stateIndexes.clone();
        }
    }
}