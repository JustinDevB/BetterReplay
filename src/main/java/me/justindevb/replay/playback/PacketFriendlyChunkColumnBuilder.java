package me.justindevb.replay.playback;

import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTLimiter;
import com.github.retrooper.packetevents.protocol.nbt.serializer.DefaultNBTSerializer;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.biome.Biome;
import com.github.retrooper.packetevents.protocol.world.biome.Biomes;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityType;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import me.justindevb.replay.chunk.ChunkCoordinate;
import me.justindevb.replay.storage.binary.BinaryPacketFriendlyChunkPayloadCodec;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

final class PacketFriendlyChunkColumnBuilder {

    private static final String AIR_BLOCK = "minecraft:air";
    private static final String PLAINS_BIOME = "minecraft:plains";
    private static final byte[][] EMPTY_LIGHT_ARRAYS = new byte[0][];
    private static final Map<ClientVersion, Map<String, Integer>> BLOCK_STATE_ID_CACHE = new ConcurrentHashMap<>();
    private static final Map<ClientVersion, Integer> AIR_BLOCK_STATE_ID_CACHE = new ConcurrentHashMap<>();
    private static final Method GET_BIOME_DATA_METHOD = resolveChunkMethod("getBiomeData");
    private static final Method SET_PALETTE_VALUE_METHOD = resolvePaletteSetMethod();

    record PreparedChunkPacket(Column column, LightData lightData) {
        PreparedChunkPacket {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(lightData, "lightData");
        }
    }

    PreparedChunkPacket prepare(
            ChunkCoordinate coordinate,
            BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload payload,
            ClientVersion clientVersion
    ) throws IOException {
        return new PreparedChunkPacket(
                build(coordinate, payload, clientVersion),
                buildLightData(payload));
    }

    Column build(
            ChunkCoordinate coordinate,
            BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload payload,
            ClientVersion clientVersion
    ) throws IOException {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(clientVersion, "clientVersion");

        BaseChunk[] sections = new BaseChunk[payload.sections().size()];
        for (int sectionIndex = 0; sectionIndex < payload.sections().size(); sectionIndex++) {
            sections[sectionIndex] = buildSection(payload.sections().get(sectionIndex), clientVersion);
        }

        TileEntity[] tileEntities = buildTileEntities(payload, clientVersion);
        return new Column(coordinate.chunkX(), coordinate.chunkZ(), true, sections, tileEntities);
    }

    LightData buildLightData(BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload payload) {
        Objects.requireNonNull(payload, "payload");

        return new LightData(
                false,
                new BitSet(),
                new BitSet(),
                new BitSet(),
                new BitSet(),
                0,
                0,
                EMPTY_LIGHT_ARRAYS,
                EMPTY_LIGHT_ARRAYS);
    }

    private Chunk_v1_18 buildSection(
            BinaryPacketFriendlyChunkPayloadCodec.SectionPayload section,
            ClientVersion clientVersion
    ) throws IOException {
        Chunk_v1_18 chunk = new Chunk_v1_18(clientVersion);

        int[] resolvedBlockPalette = resolveBlockPaletteStateIds(clientVersion, section.blockPalette());
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int logicalIndex = (y << 8) | (z << 4) | x;
                    int paletteIndex = decodePackedIndex(section.blockBitsPerEntry(), section.blockWords(), logicalIndex);
                    int resolvedPaletteIndex = Math.min(paletteIndex, resolvedBlockPalette.length - 1);
                    chunk.set(x, y, z, resolvedBlockPalette[resolvedPaletteIndex]);
                }
            }
        }

        Object biomeData = getBiomeData(chunk);
        int[] resolvedBiomePalette = resolveBiomePaletteIds(clientVersion, section.biomePalette());
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    int logicalIndex = (y << 4) | (z << 2) | x;
                    int paletteIndex = decodePackedIndex(section.biomeBitsPerEntry(), section.biomeWords(), logicalIndex);
                    setPaletteValue(biomeData, x, y, z, resolvedBiomePalette[Math.min(paletteIndex, resolvedBiomePalette.length - 1)]);
                }
            }
        }

        return chunk;
    }

    private TileEntity[] buildTileEntities(
            BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload payload,
            ClientVersion clientVersion
    ) throws IOException {
        List<TileEntity> tileEntities = new ArrayList<>(payload.blockEntities().size());
        int minBlockY = payload.minSectionY() << 4;
        for (BinaryPacketFriendlyChunkPayloadCodec.BlockEntityPayload blockEntity : payload.blockEntities()) {
            BlockEntityType type = BlockEntityTypes.getByName(blockEntity.typeKey());
            if (type == null || !type.isRegistered()) {
                continue;
            }
            NBTCompound nbt = decodeNbt(blockEntity.nbtBytes());
            tileEntities.add(new TileEntity(
                    (byte) ((blockEntity.localX() << 4) | blockEntity.localZ()),
                    (short) (minBlockY + blockEntity.yOffset()),
                    type.getId(clientVersion),
                    nbt));
        }
        return tileEntities.toArray(TileEntity[]::new);
    }

    static int[] resolveBlockPaletteStateIds(ClientVersion clientVersion, List<String> blockPalette) {
        Objects.requireNonNull(clientVersion, "clientVersion");
        return resolvePaletteIds(blockPalette, key -> resolveBlockStateId(clientVersion, key), resolveAirBlockStateId(clientVersion));
    }

    static int[] resolveBiomePaletteIds(ClientVersion clientVersion, List<String> biomePalette) {
        Objects.requireNonNull(clientVersion, "clientVersion");
        return resolvePaletteIds(biomePalette, key -> resolveBiome(clientVersion, key).getId(clientVersion), resolveBiome(clientVersion, PLAINS_BIOME).getId(clientVersion));
    }

    static boolean[] resolveFluidPaletteStates(List<String> blockPalette) {
        Objects.requireNonNull(blockPalette, "blockPalette");

        if (blockPalette.isEmpty()) {
            return new boolean[]{false};
        }

        boolean[] fluidPaletteStates = new boolean[blockPalette.size()];
        for (int index = 0; index < blockPalette.size(); index++) {
            fluidPaletteStates[index] = hasFluidState(blockPalette.get(index));
        }
        return fluidPaletteStates;
    }

    static int resolveBlockStateId(ClientVersion clientVersion, String blockStateString) {
        Objects.requireNonNull(clientVersion, "clientVersion");

        String normalizedBlockState = (blockStateString == null || blockStateString.isBlank())
                ? AIR_BLOCK
                : blockStateString;
        return BLOCK_STATE_ID_CACHE
                .computeIfAbsent(clientVersion, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(normalizedBlockState, key -> resolveBlockStateIdUncached(clientVersion, key));
    }

    private static int resolveBlockStateIdUncached(ClientVersion clientVersion, String blockStateString) {
        WrappedBlockState state = WrappedBlockState.getByString(clientVersion, blockStateString);
        if (state != null) {
            return state.getGlobalId();
        }
        return resolveAirBlockStateId(clientVersion);
    }

    private static int resolveAirBlockStateId(ClientVersion clientVersion) {
        return AIR_BLOCK_STATE_ID_CACHE.computeIfAbsent(clientVersion, ignored -> {
            WrappedBlockState airState = WrappedBlockState.getByString(clientVersion, AIR_BLOCK);
            return airState != null ? airState.getGlobalId() : 0;
        });
    }

    static int[] resolvePaletteIds(List<String> palette, ToIntFunction<String> resolver, int emptyValue) {
        Objects.requireNonNull(palette, "palette");
        Objects.requireNonNull(resolver, "resolver");

        if (palette.isEmpty()) {
            return new int[]{emptyValue};
        }

        int[] resolvedPalette = new int[palette.size()];
        for (int index = 0; index < palette.size(); index++) {
            resolvedPalette[index] = resolver.applyAsInt(palette.get(index));
        }
        return resolvedPalette;
    }

    private static Biome resolveBiome(ClientVersion clientVersion, String biomeKey) {
        Biome biome = Biomes.getRegistry().getByName(clientVersion, biomeKey);
        if (biome != null && biome.isRegistered()) {
            return biome;
        }
        return Biomes.getRegistry().getByName(clientVersion, PLAINS_BIOME);
    }

    private static NBTCompound decodeNbt(byte[] nbtBytes) throws IOException {
        if (nbtBytes.length == 0) {
            return new NBTCompound();
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(nbtBytes))) {
            NBT nbt = DefaultNBTSerializer.INSTANCE.deserializeTag(NBTLimiter.noop(), input, true);
            if (nbt instanceof NBTCompound compound) {
                return compound;
            }
            throw new IOException("Block entity NBT payload must decode to a compound");
        }
    }

    static boolean hasFluidState(String blockStateString) {
        if (blockStateString == null || blockStateString.isBlank()) {
            return false;
        }

        return blockStateString.startsWith("minecraft:water")
                || blockStateString.startsWith("minecraft:lava")
                || blockStateString.startsWith("minecraft:bubble_column")
                || blockStateString.contains("waterlogged=true");
    }

    private static Object getBiomeData(Chunk_v1_18 chunk) throws IOException {
        try {
            return GET_BIOME_DATA_METHOD.invoke(chunk);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IOException("Failed to read biome palette from chunk section", ex);
        }
    }

    private static void setPaletteValue(Object palette, int x, int y, int z, int value) throws IOException {
        try {
            SET_PALETTE_VALUE_METHOD.invoke(palette, x, y, z, value);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IOException("Failed to set palette data on chunk section", ex);
        }
    }

    private static Method resolveChunkMethod(String name, Class<?>... parameterTypes) {
        try {
            return Chunk_v1_18.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static Method resolvePaletteSetMethod() {
        try {
            Class<?> paletteClass = GET_BIOME_DATA_METHOD.getReturnType();
            return paletteClass.getMethod("set", int.class, int.class, int.class, int.class);
        } catch (NoSuchMethodException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static int decodePackedIndex(int bitsPerEntry, long[] words, int index) {
        if (bitsPerEntry == 0 || words.length == 0) {
            return 0;
        }

        long bitIndex = (long) index * bitsPerEntry;
        int wordIndex = (int) (bitIndex >>> 6);
        int bitOffset = (int) (bitIndex & 63L);
        long value = words[wordIndex] >>> bitOffset;
        int spillBits = bitOffset + bitsPerEntry - Long.SIZE;
        if (spillBits > 0 && wordIndex + 1 < words.length) {
            value |= words[wordIndex + 1] << (bitsPerEntry - spillBits);
        }
        long mask = (1L << bitsPerEntry) - 1L;
        return (int) (value & mask);
    }
}
