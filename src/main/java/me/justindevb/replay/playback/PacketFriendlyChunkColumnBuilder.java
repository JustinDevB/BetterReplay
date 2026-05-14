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
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import me.justindevb.replay.chunk.ChunkCoordinate;
import me.justindevb.replay.storage.binary.BinaryPacketFriendlyChunkPayloadCodec;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
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
        DataPalette chunkData = DataPalette.createForChunk();
        DataPalette biomeData = DataPalette.createForBiome();

        int[] resolvedBlockPalette = resolveBlockPaletteStateIds(clientVersion, section.blockPalette());
        boolean[] fluidPaletteStates = resolveFluidPaletteStates(section.blockPalette());
        int airId = resolveAirBlockStateId(clientVersion);
        int blockCount = 0;
        int fluidCount = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int logicalIndex = (y << 8) | (z << 4) | x;
                    int paletteIndex = decodePackedIndex(section.blockBitsPerEntry(), section.blockWords(), logicalIndex);
                    int resolvedPaletteIndex = Math.min(paletteIndex, resolvedBlockPalette.length - 1);
                    int blockStateId = resolvedBlockPalette[resolvedPaletteIndex];
                    chunkData.set(x, y, z, blockStateId);
                    if (blockStateId != airId) {
                        blockCount++;
                    }
                    if (fluidPaletteStates[resolvedPaletteIndex]) {
                        fluidCount++;
                    }
                }
            }
        }

        int[] resolvedBiomePalette = resolveBiomePaletteIds(clientVersion, section.biomePalette());
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    int logicalIndex = (y << 4) | (z << 2) | x;
                    int paletteIndex = decodePackedIndex(section.biomeBitsPerEntry(), section.biomeWords(), logicalIndex);
                    biomeData.set(x, y, z, resolvedBiomePalette[Math.min(paletteIndex, resolvedBiomePalette.length - 1)]);
                }
            }
        }

        return createSectionChunk(clientVersion, blockCount, fluidCount, chunkData, biomeData);
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

    static Chunk_v1_18 createSectionChunk(
            ClientVersion clientVersion,
            int blockCount,
            int fluidCount,
            DataPalette chunkData,
            DataPalette biomeData
    ) throws IOException {
        try {
            Constructor<Chunk_v1_18> constructor = Chunk_v1_18.class.getConstructor(
                    ClientVersion.class,
                    int.class,
                    int.class,
                    DataPalette.class,
                    DataPalette.class);
            return constructor.newInstance(clientVersion, blockCount, fluidCount, chunkData, biomeData);
        } catch (NoSuchMethodException ignored) {
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException ex) {
            throw new IOException("Failed to instantiate chunk section", ex);
        }

        try {
            Constructor<Chunk_v1_18> constructor = Chunk_v1_18.class.getConstructor(
                    int.class,
                    int.class,
                    DataPalette.class,
                    DataPalette.class);
            return constructor.newInstance(blockCount, fluidCount, chunkData, biomeData);
        } catch (NoSuchMethodException ignored) {
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException ex) {
            throw new IOException("Failed to instantiate chunk section", ex);
        }

        try {
            Constructor<Chunk_v1_18> constructor = Chunk_v1_18.class.getConstructor(
                    ClientVersion.class,
                    int.class,
                    DataPalette.class,
                    DataPalette.class);
            Chunk_v1_18 chunk = constructor.newInstance(clientVersion, blockCount, chunkData, biomeData);
            applyFluidCount(chunk, fluidCount);
            return chunk;
        } catch (NoSuchMethodException ignored) {
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException ex) {
            throw new IOException("Failed to instantiate chunk section", ex);
        }

        try {
            Constructor<Chunk_v1_18> constructor = Chunk_v1_18.class.getConstructor(
                    int.class,
                    DataPalette.class,
                    DataPalette.class);
            Chunk_v1_18 chunk = constructor.newInstance(blockCount, chunkData, biomeData);
            applyFluidCount(chunk, fluidCount);
            return chunk;
        } catch (NoSuchMethodException ignored) {
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException ex) {
            throw new IOException("Failed to instantiate chunk section", ex);
        }

        try {
            Constructor<Chunk_v1_18> constructor = Chunk_v1_18.class.getConstructor();
            Chunk_v1_18 chunk = constructor.newInstance();
            applyBlockCount(chunk, blockCount);
            applyFluidCount(chunk, fluidCount);
            applyPalette(chunk, "setChunkData", chunkData);
            applyPalette(chunk, "setBiomeData", biomeData);
            return chunk;
        } catch (NoSuchMethodException ignored) {
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException ex) {
            throw new IOException("Failed to instantiate chunk section", ex);
        }

        throw new IOException("No compatible Chunk_v1_18 constructor was found at runtime");
    }

    private static void applyBlockCount(Chunk_v1_18 chunk, int blockCount) throws IOException {
        try {
            Method method = Chunk_v1_18.class.getMethod("setBlockCount", int.class);
            method.invoke(chunk, blockCount);
        } catch (NoSuchMethodException ignored) {
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IOException("Failed to set block count on chunk section", ex);
        }
    }

    private static void applyFluidCount(Chunk_v1_18 chunk, int fluidCount) throws IOException {
        try {
            Method method = Chunk_v1_18.class.getMethod("setFluidCount", int.class);
            method.invoke(chunk, fluidCount);
        } catch (NoSuchMethodException ignored) {
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IOException("Failed to set fluid count on chunk section", ex);
        }
    }

    private static void applyPalette(Chunk_v1_18 chunk, String methodName, DataPalette palette) throws IOException {
        try {
            Method method = Chunk_v1_18.class.getMethod(methodName, DataPalette.class);
            method.invoke(chunk, palette);
        } catch (NoSuchMethodException ignored) {
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IOException("Failed to set palette data on chunk section", ex);
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