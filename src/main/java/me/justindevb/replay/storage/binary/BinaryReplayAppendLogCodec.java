package me.justindevb.replay.storage.binary;

import me.justindevb.replay.recording.TimelineEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

final class BinaryReplayAppendLogCodec {

    @FunctionalInterface
    interface StringIndexer {
        int indexOf(String value) throws IOException;
    }

    private BinaryReplayAppendLogCodec() {
    }

    static byte[] encodeDefineString(int index, String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, index);
        writeBytes(out, BinaryEncoding.encodeLengthPrefixedString(value));
        return out.toByteArray();
    }

    static DefinedString decodeDefineString(byte[] payload) {
        Cursor cursor = new Cursor(payload);
        int index = cursor.readVarInt();
        BinaryEncoding.DecodedString decoded = cursor.readLengthPrefixedString();
        cursor.ensureFullyRead();
        return new DefinedString(index, decoded.value());
    }

    static byte[] encodeEvent(TimelineEvent event, StringIndexer stringIndexer) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        switch (event) {
            case TimelineEvent.PlayerMove e -> {
                writeInt(out, e.tick());
                writeStringRef(out, stringIndexer, e.uuid());
                writeNullableStringRef(out, stringIndexer, e.name());
                writeNullableStringRef(out, stringIndexer, e.world());
                writeDouble(out, e.x());
                writeDouble(out, e.y());
                writeDouble(out, e.z());
                writeFloat(out, e.yaw());
                writeFloat(out, e.pitch());
                writeNullableStringRef(out, stringIndexer, e.pose());
            }
            case TimelineEvent.EntityMove e -> {
                writeInt(out, e.tick());
                writeStringRef(out, stringIndexer, e.uuid());
                writeNullableStringRef(out, stringIndexer, e.etype());
                writeNullableStringRef(out, stringIndexer, e.world());
                writeDouble(out, e.x());
                writeDouble(out, e.y());
                writeDouble(out, e.z());
                writeFloat(out, e.yaw());
                writeFloat(out, e.pitch());
            }
            case TimelineEvent.InventoryUpdate e -> {
                writeInt(out, e.tick());
                writeStringRef(out, stringIndexer, e.uuid());
                writeNullableStringRef(out, stringIndexer, e.mainHand());
                writeNullableStringRef(out, stringIndexer, e.offHand());
                writeNullableStringList(out, stringIndexer, e.armor());
                writeNullableStringList(out, stringIndexer, e.contents());
            }
            case TimelineEvent.HeldItemChange e -> {
                writeInt(out, e.tick());
                writeStringRef(out, stringIndexer, e.uuid());
                writeNullableStringRef(out, stringIndexer, e.mainHand());
                writeNullableStringRef(out, stringIndexer, e.offHand());
            }
            case TimelineEvent.BlockBreak e -> {
                writeInt(out, e.tick());
                writeStringRef(out, stringIndexer, e.uuid());
                writeNullableStringRef(out, stringIndexer, e.world());
                writeInt(out, e.x());
                writeInt(out, e.y());
                writeInt(out, e.z());
                writeNullableStringRef(out, stringIndexer, e.blockData());
            }
            case TimelineEvent.BlockBreakComplete e -> {
                writeInt(out, e.tick());
                writeStringRef(out, stringIndexer, e.uuid());
                writeNullableStringRef(out, stringIndexer, e.world());
                writeInt(out, e.x());
                writeInt(out, e.y());
                writeInt(out, e.z());
            }
            case TimelineEvent.BlockBreakStage e -> {
                writeInt(out, e.tick());
                writeNullableStringRef(out, stringIndexer, e.uuid());
                writeNullableStringRef(out, stringIndexer, e.world());
                writeInt(out, e.x());
                writeInt(out, e.y());
                writeInt(out, e.z());
                writeInt(out, e.stage());
            }
            case TimelineEvent.BlockPlace e -> {
                writeInt(out, e.tick());
                writeStringRef(out, stringIndexer, e.uuid());
                writeNullableStringRef(out, stringIndexer, e.world());
                writeInt(out, e.x());
                writeInt(out, e.y());
                writeInt(out, e.z());
                writeNullableStringRef(out, stringIndexer, e.blockData());
                writeNullableStringRef(out, stringIndexer, e.replacedBlockData());
            }
            case TimelineEvent.ItemDrop e -> {
                writeInt(out, e.tick());
                writeStringRef(out, stringIndexer, e.uuid());
                writeNullableStringRef(out, stringIndexer, e.item());
                writeNullableStringRef(out, stringIndexer, e.locWorld());
                writeDouble(out, e.locX());
                writeDouble(out, e.locY());
                writeDouble(out, e.locZ());
                writeFloat(out, e.locYaw());
                writeFloat(out, e.locPitch());
            }
            case TimelineEvent.Attack e -> {
                writeInt(out, e.tick());
                writeStringRef(out, stringIndexer, e.uuid());
                writeNullableStringRef(out, stringIndexer, e.targetUuid());
                writeNullableStringRef(out, stringIndexer, e.entityUuid());
                writeNullableStringRef(out, stringIndexer, e.entityType());
            }
            case TimelineEvent.Swing e -> {
                writeInt(out, e.tick());
                writeStringRef(out, stringIndexer, e.uuid());
                writeNullableStringRef(out, stringIndexer, e.hand());
            }
            case TimelineEvent.Damaged e -> {
                writeInt(out, e.tick());
                writeStringRef(out, stringIndexer, e.uuid());
                writeNullableStringRef(out, stringIndexer, e.entityType());
                writeNullableStringRef(out, stringIndexer, e.cause());
                writeDouble(out, e.finalDamage());
            }
            case TimelineEvent.SprintToggle e -> {
                writeInt(out, e.tick());
                writeStringRef(out, stringIndexer, e.uuid());
                writeBoolean(out, e.sprinting());
            }
            case TimelineEvent.SneakToggle e -> {
                writeInt(out, e.tick());
                writeStringRef(out, stringIndexer, e.uuid());
                writeBoolean(out, e.sneaking());
            }
            case TimelineEvent.EntitySpawn e -> {
                writeInt(out, e.tick());
                writeStringRef(out, stringIndexer, e.uuid());
                writeNullableStringRef(out, stringIndexer, e.etype());
                writeNullableStringRef(out, stringIndexer, e.world());
                writeDouble(out, e.x());
                writeDouble(out, e.y());
                writeDouble(out, e.z());
            }
            case TimelineEvent.EntityDeath e -> {
                writeInt(out, e.tick());
                writeStringRef(out, stringIndexer, e.uuid());
                writeNullableStringRef(out, stringIndexer, e.etype());
                writeNullableStringRef(out, stringIndexer, e.world());
                writeDouble(out, e.x());
                writeDouble(out, e.y());
                writeDouble(out, e.z());
            }
            case TimelineEvent.PlayerQuit e -> {
                writeInt(out, e.tick());
                writeStringRef(out, stringIndexer, e.uuid());
            }
        }
        return out.toByteArray();
    }

    static TimelineEvent decodeEvent(BinaryRecordType type, byte[] payload, List<String> stringTable) {
        Cursor cursor = new Cursor(payload);
        return switch (type) {
            case PLAYER_MOVE -> {
                TimelineEvent.PlayerMove event = new TimelineEvent.PlayerMove(
                        cursor.readInt(),
                        cursor.readStringRef(stringTable),
                    cursor.readNullableStringRef(stringTable),
                    cursor.readNullableStringRef(stringTable),
                        cursor.readDouble(),
                        cursor.readDouble(),
                        cursor.readDouble(),
                        cursor.readFloat(),
                        cursor.readFloat(),
                        cursor.readNullableStringRef(stringTable));
                cursor.ensureFullyRead();
                yield event;
            }
            case ENTITY_MOVE -> {
                TimelineEvent.EntityMove event = new TimelineEvent.EntityMove(
                        cursor.readInt(),
                        cursor.readStringRef(stringTable),
                    cursor.readNullableStringRef(stringTable),
                    cursor.readNullableStringRef(stringTable),
                        cursor.readDouble(),
                        cursor.readDouble(),
                        cursor.readDouble(),
                        cursor.readFloat(),
                        cursor.readFloat());
                cursor.ensureFullyRead();
                yield event;
            }
            case INVENTORY_UPDATE -> {
                TimelineEvent.InventoryUpdate event = new TimelineEvent.InventoryUpdate(
                        cursor.readInt(),
                        cursor.readStringRef(stringTable),
                        cursor.readNullableStringRef(stringTable),
                        cursor.readNullableStringRef(stringTable),
                        cursor.readNullableStringList(stringTable),
                        cursor.readNullableStringList(stringTable));
                cursor.ensureFullyRead();
                yield event;
            }
            case HELD_ITEM_CHANGE -> {
                TimelineEvent.HeldItemChange event = new TimelineEvent.HeldItemChange(
                        cursor.readInt(),
                        cursor.readStringRef(stringTable),
                        cursor.readNullableStringRef(stringTable),
                        cursor.readNullableStringRef(stringTable));
                cursor.ensureFullyRead();
                yield event;
            }
            case BLOCK_BREAK -> {
                TimelineEvent.BlockBreak event = new TimelineEvent.BlockBreak(
                        cursor.readInt(),
                        cursor.readStringRef(stringTable),
                    cursor.readNullableStringRef(stringTable),
                        cursor.readInt(),
                        cursor.readInt(),
                        cursor.readInt(),
                        cursor.readNullableStringRef(stringTable));
                cursor.ensureFullyRead();
                yield event;
            }
            case BLOCK_BREAK_COMPLETE -> {
                TimelineEvent.BlockBreakComplete event = new TimelineEvent.BlockBreakComplete(
                        cursor.readInt(),
                        cursor.readStringRef(stringTable),
                    cursor.readNullableStringRef(stringTable),
                        cursor.readInt(),
                        cursor.readInt(),
                        cursor.readInt());
                cursor.ensureFullyRead();
                yield event;
            }
            case BLOCK_BREAK_STAGE -> {
                TimelineEvent.BlockBreakStage event = new TimelineEvent.BlockBreakStage(
                        cursor.readInt(),
                        cursor.readNullableStringRef(stringTable),
                    cursor.readNullableStringRef(stringTable),
                        cursor.readInt(),
                        cursor.readInt(),
                        cursor.readInt(),
                        cursor.readInt());
                cursor.ensureFullyRead();
                yield event;
            }
            case BLOCK_PLACE -> {
                TimelineEvent.BlockPlace event = new TimelineEvent.BlockPlace(
                        cursor.readInt(),
                        cursor.readStringRef(stringTable),
                    cursor.readNullableStringRef(stringTable),
                        cursor.readInt(),
                        cursor.readInt(),
                        cursor.readInt(),
                        cursor.readNullableStringRef(stringTable),
                        cursor.readNullableStringRef(stringTable));
                cursor.ensureFullyRead();
                yield event;
            }
            case ITEM_DROP -> {
                TimelineEvent.ItemDrop event = new TimelineEvent.ItemDrop(
                        cursor.readInt(),
                        cursor.readStringRef(stringTable),
                        cursor.readNullableStringRef(stringTable),
                        cursor.readNullableStringRef(stringTable),
                        cursor.readDouble(),
                        cursor.readDouble(),
                        cursor.readDouble(),
                        cursor.readFloat(),
                        cursor.readFloat());
                cursor.ensureFullyRead();
                yield event;
            }
            case ATTACK -> {
                TimelineEvent.Attack event = new TimelineEvent.Attack(
                        cursor.readInt(),
                        cursor.readStringRef(stringTable),
                        cursor.readNullableStringRef(stringTable),
                    cursor.readNullableStringRef(stringTable),
                    cursor.readNullableStringRef(stringTable));
                cursor.ensureFullyRead();
                yield event;
            }
            case SWING -> {
                TimelineEvent.Swing event = new TimelineEvent.Swing(
                        cursor.readInt(),
                        cursor.readStringRef(stringTable),
                        cursor.readNullableStringRef(stringTable));
                cursor.ensureFullyRead();
                yield event;
            }
            case DAMAGED -> {
                TimelineEvent.Damaged event = new TimelineEvent.Damaged(
                        cursor.readInt(),
                        cursor.readStringRef(stringTable),
                        cursor.readNullableStringRef(stringTable),
                        cursor.readNullableStringRef(stringTable),
                        cursor.readDouble());
                cursor.ensureFullyRead();
                yield event;
            }
            case SPRINT_TOGGLE -> {
                TimelineEvent.SprintToggle event = new TimelineEvent.SprintToggle(
                        cursor.readInt(),
                        cursor.readStringRef(stringTable),
                        cursor.readBoolean());
                cursor.ensureFullyRead();
                yield event;
            }
            case SNEAK_TOGGLE -> {
                TimelineEvent.SneakToggle event = new TimelineEvent.SneakToggle(
                        cursor.readInt(),
                        cursor.readStringRef(stringTable),
                        cursor.readBoolean());
                cursor.ensureFullyRead();
                yield event;
            }
            case ENTITY_SPAWN -> {
                TimelineEvent.EntitySpawn event = new TimelineEvent.EntitySpawn(
                        cursor.readInt(),
                        cursor.readStringRef(stringTable),
                    cursor.readNullableStringRef(stringTable),
                    cursor.readNullableStringRef(stringTable),
                        cursor.readDouble(),
                        cursor.readDouble(),
                        cursor.readDouble());
                cursor.ensureFullyRead();
                yield event;
            }
            case ENTITY_DEATH -> {
                TimelineEvent.EntityDeath event = new TimelineEvent.EntityDeath(
                        cursor.readInt(),
                        cursor.readStringRef(stringTable),
                    cursor.readNullableStringRef(stringTable),
                    cursor.readNullableStringRef(stringTable),
                        cursor.readDouble(),
                        cursor.readDouble(),
                        cursor.readDouble());
                cursor.ensureFullyRead();
                yield event;
            }
            case PLAYER_QUIT -> {
                TimelineEvent.PlayerQuit event = new TimelineEvent.PlayerQuit(
                        cursor.readInt(),
                        cursor.readStringRef(stringTable));
                cursor.ensureFullyRead();
                yield event;
            }
            case DEFINE_STRING -> throw new IllegalArgumentException("DEFINE_STRING is not a timeline event record");
        };
    }

    private static void writeNullableStringList(ByteArrayOutputStream out, StringIndexer stringIndexer, List<String> values) throws IOException {
        writeVarInt(out, values.size());
        for (String value : values) {
            writeNullableStringRef(out, stringIndexer, value);
        }
    }

    private static void writeStringRef(ByteArrayOutputStream out, StringIndexer stringIndexer, String value) throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("Non-null string field was null");
        }
        writeVarInt(out, stringIndexer.indexOf(value));
    }

    private static void writeNullableStringRef(ByteArrayOutputStream out, StringIndexer stringIndexer, String value) throws IOException {
        writeBoolean(out, value != null);
        if (value != null) {
            writeVarInt(out, stringIndexer.indexOf(value));
        }
    }

    private static void writeBoolean(ByteArrayOutputStream out, boolean value) {
        out.write(value ? BinaryReplayFormat.BOOLEAN_TRUE : BinaryReplayFormat.BOOLEAN_FALSE);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        writeBytes(out, ByteBuffer.allocate(Integer.BYTES).order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER).putInt(value).array());
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        writeBytes(out, ByteBuffer.allocate(Long.BYTES).order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER).putLong(value).array());
    }

    private static void writeFloat(ByteArrayOutputStream out, float value) {
        writeBytes(out, ByteBuffer.allocate(Float.BYTES).order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER).putFloat(value).array());
    }

    private static void writeDouble(ByteArrayOutputStream out, double value) {
        writeBytes(out, ByteBuffer.allocate(Double.BYTES).order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER).putDouble(value).array());
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        writeBytes(out, BinaryEncoding.encodeVarInt(value));
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] bytes) {
        out.writeBytes(bytes);
    }

    record DefinedString(int index, String value) {
    }

    static final class Cursor {
        private final byte[] bytes;
        private int offset;

        Cursor(byte[] bytes) {
            this.bytes = bytes;
        }

        int readVarInt() {
            int value = 0;
            int shift = 0;
            while (offset < bytes.length) {
                int current = bytes[offset++] & 0xFF;
                value |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IllegalArgumentException("VarInt is too large");
                }
            }
            throw new IllegalArgumentException("Unexpected end of bytes while reading VarInt");
        }

        boolean readBoolean() {
            ensureRemaining(1);
            int value = bytes[offset++] & 0xFF;
            if (!BinaryReplayFormat.isValidBooleanEncoding(value)) {
                throw new IllegalArgumentException("Invalid boolean encoding: " + value);
            }
            return value == BinaryReplayFormat.BOOLEAN_TRUE;
        }

        int readInt() {
            ensureRemaining(Integer.BYTES);
            int value = ByteBuffer.wrap(bytes, offset, Integer.BYTES)
                    .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                    .getInt();
            offset += Integer.BYTES;
            return value;
        }

        long readLong() {
            ensureRemaining(Long.BYTES);
            long value = ByteBuffer.wrap(bytes, offset, Long.BYTES)
                    .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                    .getLong();
            offset += Long.BYTES;
            return value;
        }

        float readFloat() {
            ensureRemaining(Float.BYTES);
            float value = ByteBuffer.wrap(bytes, offset, Float.BYTES)
                    .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                    .getFloat();
            offset += Float.BYTES;
            return value;
        }

        double readDouble() {
            ensureRemaining(Double.BYTES);
            double value = ByteBuffer.wrap(bytes, offset, Double.BYTES)
                    .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                    .getDouble();
            offset += Double.BYTES;
            return value;
        }

        BinaryEncoding.DecodedString readLengthPrefixedString() {
            int start = offset;
            int length = readVarInt();
            int prefixLength = offset - start;
            ensureRemaining(length);
            BinaryEncoding.DecodedString decoded = BinaryEncoding.decodeLengthPrefixedString(copyOfRange(start, prefixLength + length));
            offset += length;
            return decoded;
        }

        String readStringRef(List<String> stringTable) {
            int index = readVarInt();
            if (index < 0 || index >= stringTable.size()) {
                throw new IllegalArgumentException("Invalid string-table reference: " + index);
            }
            return stringTable.get(index);
        }

        String readNullableStringRef(List<String> stringTable) {
            return readBoolean() ? readStringRef(stringTable) : null;
        }

        List<String> readNullableStringList(List<String> stringTable) {
            int size = readVarInt();
            List<String> values = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                values.add(readNullableStringRef(stringTable));
            }
            return values;
        }

        byte[] remainingBytes() {
            return copyOfRange(offset, bytes.length - offset);
        }

        byte[] readBytes(int length) {
            ensureRemaining(length);
            byte[] copy = copyOfRange(offset, length);
            offset += length;
            return copy;
        }

        void ensureFullyRead() {
            if (offset != bytes.length) {
                throw new IllegalArgumentException("Unexpected trailing bytes in append-log record payload");
            }
        }

        private byte[] copyOfRange(int start, int length) {
            byte[] copy = new byte[length];
            System.arraycopy(bytes, start, copy, 0, length);
            return copy;
        }

        private void ensureRemaining(int count) {
            if (offset + count > bytes.length) {
                throw new IllegalArgumentException("Unexpected end of append-log payload");
            }
        }
    }
}