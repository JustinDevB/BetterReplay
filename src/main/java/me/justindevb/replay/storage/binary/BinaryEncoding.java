package me.justindevb.replay.storage.binary;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Exact primitive encodings for BetterReplay binary payloads.
 */
public final class BinaryEncoding {

    private BinaryEncoding() {
    }

    public static byte[] encodeVarInt(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("VarInt values must be non-negative in the binary replay format");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(BinaryReplayFormat.VAR_INT_MAX_BYTES);
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
        return out.toByteArray();
    }

    public static int decodeVarInt(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > BinaryReplayFormat.VAR_INT_MAX_BYTES) {
            throw new IllegalArgumentException("VarInt byte sequence length is invalid: " + bytes.length);
        }

        int value = 0;
        int shift = 0;
        for (int index = 0; index < bytes.length; index++) {
            int current = bytes[index] & 0xFF;
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                if (index != bytes.length - 1) {
                    throw new IllegalArgumentException("VarInt contains trailing bytes after terminal byte");
                }
                return value;
            }
            shift += 7;
        }

        throw new IllegalArgumentException("VarInt does not terminate within " + BinaryReplayFormat.VAR_INT_MAX_BYTES + " bytes");
    }

    public static byte[] encodeLengthPrefixedString(String value) {
        byte[] stringBytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        byte[] lengthBytes = encodeVarInt(stringBytes.length);
        ByteBuffer buffer = ByteBuffer.allocate(lengthBytes.length + stringBytes.length);
        buffer.put(lengthBytes);
        buffer.put(stringBytes);
        return buffer.array();
    }

    public static DecodedString decodeLengthPrefixedString(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        int length = 0;
        int shift = 0;
        int offset = 0;

        while (offset < bytes.length) {
            int current = bytes[offset] & 0xFF;
            length |= (current & 0x7F) << shift;
            offset++;
            if ((current & 0x80) == 0) {
                if (bytes.length - offset != length) {
                    throw new IllegalArgumentException("Length-prefixed string payload length mismatch");
                }
                return new DecodedString(new String(bytes, offset, length, StandardCharsets.UTF_8), offset);
            }
            shift += 7;
            if (offset == BinaryReplayFormat.VAR_INT_MAX_BYTES) {
                throw new IllegalArgumentException("String length prefix exceeds supported VarInt size");
            }
        }

        throw new IllegalArgumentException("String length prefix did not terminate");
    }

    public record DecodedString(String value, int payloadOffset) {
    }
}