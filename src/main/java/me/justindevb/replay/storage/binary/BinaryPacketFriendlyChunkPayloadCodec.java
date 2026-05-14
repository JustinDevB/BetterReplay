package me.justindevb.replay.storage.binary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class BinaryPacketFriendlyChunkPayloadCodec {

    private static final byte[] MAGIC = new byte[] {'B', 'R', 'C', 'P'};
    private static final int VERSION = 1;
    private static final int FIXED_HEADER_BYTES = 16;
    private static final int BLOCKS_PER_SECTION = 16 * 16 * 16;
    private static final int BIOMES_PER_SECTION = 4 * 4 * 4;
    private static final int FLAG_HAS_BIOMES = 0x01;
    private static final int FLAG_HAS_BLOCK_ENTITIES = 0x02;
    private static final int FLAG_STORES_HEIGHTMAPS = 0x04;
    private static final int FLAG_STORES_LIGHT = 0x08;
    private static final int SUPPORTED_FLAGS = FLAG_HAS_BIOMES | FLAG_HAS_BLOCK_ENTITIES;

    public byte[] encode(PacketFriendlyChunkPayload payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.sections().isEmpty()) {
            throw new IllegalArgumentException("sections must not be empty");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        out.write(VERSION);
        out.write(flags(payload.blockEntities().isEmpty()));
        out.write(0x00);
        out.write(0x00);
        out.writeBytes(littleEndianInt(payload.minSectionY()));
        out.writeBytes(littleEndianInt(payload.sections().size()));
        out.writeBytes(BinaryEncoding.encodeVarInt(payload.blockEntities().size()));

        for (SectionPayload section : payload.sections()) {
            writePaletteSection(out, section.blockPalette(), section.blockBitsPerEntry(), section.blockWords(), BLOCKS_PER_SECTION, "block");
            writePaletteSection(out, section.biomePalette(), section.biomeBitsPerEntry(), section.biomeWords(), BIOMES_PER_SECTION, "biome");
        }

        for (BlockEntityPayload blockEntity : payload.blockEntities()) {
            validateBlockEntity(blockEntity);
            out.write((blockEntity.localX() << 4) | blockEntity.localZ());
            out.writeBytes(BinaryEncoding.encodeVarInt(blockEntity.yOffset()));
            out.writeBytes(BinaryEncoding.encodeLengthPrefixedString(blockEntity.typeKey()));
            out.writeBytes(BinaryEncoding.encodeVarInt(blockEntity.nbtBytes().length));
            out.writeBytes(blockEntity.nbtBytes());
        }

        return out.toByteArray();
    }

    public PacketFriendlyChunkPayload decode(byte[] bytes) throws IOException {
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

        int flags = bytes[5] & 0xFF;
        if ((flags & FLAG_HAS_BIOMES) == 0) {
            throw new IOException("Packet-friendly chunk payload must store biome data");
        }
        if ((flags & ~SUPPORTED_FLAGS) != 0 || (flags & FLAG_STORES_HEIGHTMAPS) != 0 || (flags & FLAG_STORES_LIGHT) != 0) {
            throw new IOException("Unsupported packet-friendly chunk payload flags: " + flags);
        }
        if (bytes[6] != 0 || bytes[7] != 0) {
            throw new IOException("Chunk payload reserved bytes must be zero");
        }

        ByteBuffer header = ByteBuffer.wrap(bytes, 8, 8).order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER);
        int minSectionY = header.getInt();
        int sectionCount = header.getInt();
        if (sectionCount <= 0) {
            throw new IOException("Chunk payload sectionCount must be positive");
        }

        Cursor cursor = new Cursor(bytes, FIXED_HEADER_BYTES);
        int blockEntityCount = cursor.readVarInt();
        List<SectionPayload> sections = new ArrayList<>(sectionCount);
        for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
            sections.add(new SectionPayload(
                    cursor.readPalette("block", BLOCKS_PER_SECTION),
                    cursor.readUnsignedByte(),
                    cursor.readPackedWords("block", BLOCKS_PER_SECTION),
                    cursor.readPalette("biome", BIOMES_PER_SECTION),
                    cursor.readUnsignedByte(),
                    cursor.readPackedWords("biome", BIOMES_PER_SECTION)));
            SectionPayload decodedSection = sections.get(sectionIndex);
            validatePaletteSection(decodedSection.blockPalette(), decodedSection.blockBitsPerEntry(), decodedSection.blockWords(), BLOCKS_PER_SECTION, "block");
            validatePaletteSection(decodedSection.biomePalette(), decodedSection.biomeBitsPerEntry(), decodedSection.biomeWords(), BIOMES_PER_SECTION, "biome");
        }

        List<BlockEntityPayload> blockEntities = new ArrayList<>(blockEntityCount);
        for (int index = 0; index < blockEntityCount; index++) {
            int packedXZ = cursor.readUnsignedByte();
            int localX = (packedXZ >>> 4) & 0x0F;
            int localZ = packedXZ & 0x0F;
            int yOffset = cursor.readVarInt();
            String typeKey = cursor.readString();
            byte[] nbtBytes = cursor.readByteArray();
            blockEntities.add(new BlockEntityPayload(localX, yOffset, localZ, typeKey, nbtBytes));
        }

        if (cursor.remaining() != 0) {
            throw new IOException("Chunk payload contains trailing bytes");
        }
        if (((flags & FLAG_HAS_BLOCK_ENTITIES) != 0) != !blockEntities.isEmpty()) {
            throw new IOException("Chunk payload block entity flag does not match the decoded payload");
        }

        return new PacketFriendlyChunkPayload(minSectionY, sections, blockEntities);
    }

    private static void writePaletteSection(
            ByteArrayOutputStream out,
            List<String> palette,
            int bitsPerEntry,
            long[] words,
            int cellCount,
            String label
    ) {
        validatePaletteSection(palette, bitsPerEntry, words, cellCount, label);
        out.writeBytes(BinaryEncoding.encodeVarInt(palette.size()));
        for (String value : palette) {
            out.writeBytes(BinaryEncoding.encodeLengthPrefixedString(value));
        }
        out.write(bitsPerEntry);
        out.writeBytes(BinaryEncoding.encodeVarInt(words.length));
        for (long word : words) {
            out.writeBytes(littleEndianLong(word));
        }
    }

    private static void validatePaletteSection(
            List<String> palette,
            int bitsPerEntry,
            long[] words,
            int cellCount,
            String label
    ) {
        Objects.requireNonNull(palette, label + "Palette");
        Objects.requireNonNull(words, label + "Words");
        if (palette.isEmpty()) {
            throw new IllegalArgumentException(label + " palette must not be empty");
        }
        palette.forEach(value -> {
            if (Objects.requireNonNull(value, label + "Palette entry").isBlank()) {
                throw new IllegalArgumentException(label + " palette entries must not be blank");
            }
        });
        if (bitsPerEntry < 0 || bitsPerEntry > 31) {
            throw new IllegalArgumentException(label + " bitsPerEntry must be between 0 and 31");
        }
        if (palette.size() == 1) {
            if (bitsPerEntry != 0) {
                throw new IllegalArgumentException(label + " bitsPerEntry must be zero when the palette has one entry");
            }
        } else {
            if (bitsPerEntry == 0) {
                throw new IllegalArgumentException(label + " bitsPerEntry must be positive when the palette has multiple entries");
            }
            long encodableValues = 1L << bitsPerEntry;
            if (palette.size() > encodableValues) {
                throw new IllegalArgumentException(label + " palette does not fit inside the configured bitsPerEntry");
            }
        }

        int expectedWordCount = expectedWordCount(cellCount, bitsPerEntry);
        if (words.length != expectedWordCount) {
            throw new IllegalArgumentException(label + " packed word count mismatch");
        }
    }

    private static void validateBlockEntity(BlockEntityPayload blockEntity) {
        Objects.requireNonNull(blockEntity, "blockEntity");
        if (blockEntity.localX() < 0 || blockEntity.localX() > 15) {
            throw new IllegalArgumentException("blockEntity localX must be between 0 and 15");
        }
        if (blockEntity.localZ() < 0 || blockEntity.localZ() > 15) {
            throw new IllegalArgumentException("blockEntity localZ must be between 0 and 15");
        }
        if (blockEntity.yOffset() < 0) {
            throw new IllegalArgumentException("blockEntity yOffset must be non-negative");
        }
        if (Objects.requireNonNull(blockEntity.typeKey(), "typeKey").isBlank()) {
            throw new IllegalArgumentException("blockEntity typeKey must not be blank");
        }
        Objects.requireNonNull(blockEntity.nbtBytes(), "nbtBytes");
    }

    private static int expectedWordCount(int cellCount, int bitsPerEntry) {
        if (bitsPerEntry == 0) {
            return 0;
        }
        long totalBits = (long) cellCount * bitsPerEntry;
        return Math.toIntExact((totalBits + Long.SIZE - 1) / Long.SIZE);
    }

    private static int flags(boolean noBlockEntities) {
        return FLAG_HAS_BIOMES | (noBlockEntities ? 0 : FLAG_HAS_BLOCK_ENTITIES);
    }

    private static byte[] littleEndianInt(int value) {
        return ByteBuffer.allocate(Integer.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putInt(value)
                .array();
    }

    private static byte[] littleEndianLong(long value) {
        return ByteBuffer.allocate(Long.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putLong(value)
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

        private int readUnsignedByte() throws IOException {
            if (remaining() < 1) {
                throw new IOException("Chunk payload byte was truncated");
            }
            return bytes[offset++] & 0xFF;
        }

        private int readVarInt() throws IOException {
            int value = 0;
            int shift = 0;
            for (int consumed = 0; consumed < BinaryReplayFormat.VAR_INT_MAX_BYTES; consumed++) {
                if (remaining() == 0) {
                    throw new IOException("Chunk payload VarInt was truncated");
                }
                int current = bytes[offset++] & 0xFF;
                value |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    return value;
                }
                shift += 7;
            }
            throw new IOException("Chunk payload VarInt exceeds supported size");
        }

        private String readString() throws IOException {
            int length = readVarInt();
            if (length > remaining()) {
                throw new IOException("Chunk payload string exceeds available bytes");
            }
            String value = new String(bytes, offset, length, BinaryReplayFormat.STRING_CHARSET);
            offset += length;
            return value;
        }

        private byte[] readByteArray() throws IOException {
            int length = readVarInt();
            if (length > remaining()) {
                throw new IOException("Chunk payload byte array exceeds available bytes");
            }
            byte[] value = Arrays.copyOfRange(bytes, offset, offset + length);
            offset += length;
            return value;
        }

        private List<String> readPalette(String label, int cellCount) throws IOException {
            int paletteSize = readVarInt();
            if (paletteSize <= 0) {
                throw new IOException(label + " palette must not be empty");
            }
            List<String> palette = new ArrayList<>(paletteSize);
            for (int index = 0; index < paletteSize; index++) {
                palette.add(readString());
            }
            return List.copyOf(palette);
        }

        private long[] readPackedWords(String label, int cellCount) throws IOException {
            int wordCount = readVarInt();
            if (wordCount < 0) {
                throw new IOException(label + " packed word count must be non-negative");
            }
            if (remaining() < wordCount * Long.BYTES) {
                throw new IOException(label + " packed word payload was truncated");
            }
            long[] words = new long[wordCount];
            for (int index = 0; index < wordCount; index++) {
                words[index] = ByteBuffer.wrap(bytes, offset, Long.BYTES)
                        .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                        .getLong();
                offset += Long.BYTES;
            }
            return words;
        }
    }

    public record PacketFriendlyChunkPayload(
            int minSectionY,
            List<SectionPayload> sections,
            List<BlockEntityPayload> blockEntities
    ) {

        public PacketFriendlyChunkPayload {
            sections = List.copyOf(sections);
            blockEntities = List.copyOf(blockEntities);
        }
    }

    public record SectionPayload(
            List<String> blockPalette,
            int blockBitsPerEntry,
            long[] blockWords,
            List<String> biomePalette,
            int biomeBitsPerEntry,
            long[] biomeWords
    ) {

        public SectionPayload {
            blockPalette = List.copyOf(blockPalette);
            blockWords = blockWords.clone();
            biomePalette = List.copyOf(biomePalette);
            biomeWords = biomeWords.clone();
        }

        @Override
        public long[] blockWords() {
            return blockWords.clone();
        }

        @Override
        public long[] biomeWords() {
            return biomeWords.clone();
        }
    }

    public record BlockEntityPayload(int localX, int yOffset, int localZ, String typeKey, byte[] nbtBytes) {

        public BlockEntityPayload {
            nbtBytes = nbtBytes.clone();
        }

        @Override
        public byte[] nbtBytes() {
            return nbtBytes.clone();
        }
    }
}