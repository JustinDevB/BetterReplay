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
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import me.justindevb.replay.chunk.ChunkCoordinate;
import me.justindevb.replay.storage.binary.BinaryPacketFriendlyChunkPayloadCodec;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class PacketFriendlyChunkColumnBuilder {

    private static final String AIR_BLOCK = "minecraft:air";
    private static final String PLAINS_BIOME = "minecraft:plains";

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

    private Chunk_v1_18 buildSection(
            BinaryPacketFriendlyChunkPayloadCodec.SectionPayload section,
            ClientVersion clientVersion
    ) {
        DataPalette chunkData = DataPalette.createForChunk();
        DataPalette biomeData = DataPalette.createForBiome();

        int airId = resolveBlockState(clientVersion, AIR_BLOCK).getGlobalId();
        int blockCount = 0;
        int fluidCount = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int logicalIndex = (y << 8) | (z << 4) | x;
                    int paletteIndex = decodePackedIndex(section.blockBitsPerEntry(), section.blockWords(), logicalIndex);
                    String blockStateString = section.blockPalette().get(Math.min(paletteIndex, section.blockPalette().size() - 1));
                    WrappedBlockState blockState = resolveBlockState(clientVersion, blockStateString);
                    chunkData.set(x, y, z, blockState.getGlobalId());
                    if (blockState.getGlobalId() != airId) {
                        blockCount++;
                    }
                    if (blockState.isFluid()) {
                        fluidCount++;
                    }
                }
            }
        }

        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    int logicalIndex = (y << 4) | (z << 2) | x;
                    int paletteIndex = decodePackedIndex(section.biomeBitsPerEntry(), section.biomeWords(), logicalIndex);
                    String biomeKey = section.biomePalette().get(Math.min(paletteIndex, section.biomePalette().size() - 1));
                    biomeData.set(x, y, z, resolveBiome(clientVersion, biomeKey).getId(clientVersion));
                }
            }
        }

        return new Chunk_v1_18(clientVersion, blockCount, fluidCount, chunkData, biomeData);
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

    private static WrappedBlockState resolveBlockState(ClientVersion clientVersion, String blockStateString) {
        WrappedBlockState state = WrappedBlockState.getByString(clientVersion, blockStateString);
        if (state != null) {
            return state;
        }
        return WrappedBlockState.getByString(clientVersion, AIR_BLOCK);
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