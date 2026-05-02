package me.justindevb.replay.chunk;

import me.justindevb.replay.storage.binary.BinaryChunkPayloadFormat;
import me.justindevb.replay.storage.binary.BinaryPacketFriendlyChunkPayloadCodec;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Biome;
import org.bukkit.block.TileState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Captures live world chunks into packet-friendly BRCP snapshot payloads.
 */
public final class WorldChunkPacketFriendlyCaptureService implements ChunkBaselineCaptureService {

    private final BinaryPacketFriendlyChunkPayloadCodec payloadCodec;

    public WorldChunkPacketFriendlyCaptureService(BinaryPacketFriendlyChunkPayloadCodec payloadCodec) {
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "payloadCodec");
    }

    @Override
    public CapturedChunkBaseline capture(ChunkCoordinate coordinate) throws IOException {
        BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload payload = capturePayload(coordinate);
        return new CapturedChunkBaseline(
                coordinate,
                payloadCodec.encode(payload),
                BinaryChunkPayloadFormat.BRCP);
    }

    public BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload capturePayload(ChunkCoordinate coordinate) throws IOException {
        World world = Bukkit.getWorld(coordinate.worldName());
        if (world == null) {
            throw new IOException("World is not available for chunk capture: " + coordinate.worldName());
        }

        int minSectionY = Math.floorDiv(world.getMinHeight(), 16);
        int sectionCount = Math.floorDiv(world.getMaxHeight() - world.getMinHeight(), 16);
        if (sectionCount <= 0) {
            throw new IOException("World section count is invalid for chunk capture: " + coordinate.worldName());
        }

        Chunk chunk = world.getChunkAt(coordinate.chunkX(), coordinate.chunkZ());
        List<BinaryPacketFriendlyChunkPayloadCodec.SectionPayload> sections = new ArrayList<>(sectionCount);
        int chunkBaseX = coordinate.chunkX() << 4;
        int chunkBaseZ = coordinate.chunkZ() << 4;

        for (int sectionOffset = 0; sectionOffset < sectionCount; sectionOffset++) {
            int sectionY = minSectionY + sectionOffset;
            sections.add(captureSection(world, chunk, chunkBaseX, chunkBaseZ, sectionY));
        }

        List<BinaryPacketFriendlyChunkPayloadCodec.BlockEntityPayload> blockEntities = captureBlockEntities(chunk, minSectionY);

        return new BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload(minSectionY, sections, blockEntities);
    }

    private static List<BinaryPacketFriendlyChunkPayloadCodec.BlockEntityPayload> captureBlockEntities(Chunk chunk, int minSectionY) {
        int minBlockY = minSectionY << 4;
        List<BinaryPacketFriendlyChunkPayloadCodec.BlockEntityPayload> blockEntities = new ArrayList<>();
        for (BlockState blockState : chunk.getTileEntities(false)) {
            if (!(blockState instanceof TileState tileState)) {
                continue;
            }

            String typeKey = resolveBlockEntityTypeKey(tileState.getType());
            if (typeKey == null) {
                continue;
            }

            int yOffset = tileState.getY() - minBlockY;
            if (yOffset < 0) {
                continue;
            }

            blockEntities.add(new BinaryPacketFriendlyChunkPayloadCodec.BlockEntityPayload(
                    Math.floorMod(tileState.getX(), 16),
                    yOffset,
                    Math.floorMod(tileState.getZ(), 16),
                    typeKey,
                    BlockEntityNbtReflectionSupport.extractNamedCompoundBytes(tileState)));
        }

        blockEntities.sort(Comparator
                .comparingInt(BinaryPacketFriendlyChunkPayloadCodec.BlockEntityPayload::yOffset)
                .thenComparingInt(BinaryPacketFriendlyChunkPayloadCodec.BlockEntityPayload::localX)
                .thenComparingInt(BinaryPacketFriendlyChunkPayloadCodec.BlockEntityPayload::localZ)
                .thenComparing(BinaryPacketFriendlyChunkPayloadCodec.BlockEntityPayload::typeKey));
        return List.copyOf(blockEntities);
    }

    private static BinaryPacketFriendlyChunkPayloadCodec.SectionPayload captureSection(
            World world,
            Chunk chunk,
            int chunkBaseX,
            int chunkBaseZ,
            int sectionY
    ) {
        int sectionBaseY = sectionY << 4;

        IndexedPalette<String> blockPalette = new IndexedPalette<>();
        int[] blockIndexes = new int[16 * 16 * 16];
        int blockWriteIndex = 0;
        for (int localY = 0; localY < 16; localY++) {
            int y = sectionBaseY + localY;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    Block block = chunk.getBlock(x, y, z);
                    blockIndexes[blockWriteIndex++] = blockPalette.indexOf(block.getBlockData().getAsString());
                }
            }
        }

        IndexedPalette<String> biomePalette = new IndexedPalette<>();
        int[] biomeIndexes = new int[4 * 4 * 4];
        int biomeWriteIndex = 0;
        for (int biomeY = 0; biomeY < 4; biomeY++) {
            int worldY = sectionBaseY + (biomeY << 2);
            for (int biomeZ = 0; biomeZ < 4; biomeZ++) {
                int worldZ = chunkBaseZ + (biomeZ << 2);
                for (int biomeX = 0; biomeX < 4; biomeX++) {
                    int worldX = chunkBaseX + (biomeX << 2);
                    biomeIndexes[biomeWriteIndex++] = biomePalette.indexOf(resolveBiomeKey(world.getBiome(worldX, worldY, worldZ)));
                }
            }
        }

        return new BinaryPacketFriendlyChunkPayloadCodec.SectionPayload(
                blockPalette.values(),
                bitsPerEntry(blockPalette.size()),
                pack(blockIndexes, bitsPerEntry(blockPalette.size())),
                biomePalette.values(),
                bitsPerEntry(biomePalette.size()),
                pack(biomeIndexes, bitsPerEntry(biomePalette.size())));
    }

    private static int bitsPerEntry(int paletteSize) {
        if (paletteSize <= 1) {
            return 0;
        }
        return Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    private static long[] pack(int[] indices, int bitsPerEntry) {
        if (bitsPerEntry == 0) {
            return new long[0];
        }

        long[] packed = new long[(int) (((long) indices.length * bitsPerEntry + Long.SIZE - 1) / Long.SIZE)];
        long mask = (1L << bitsPerEntry) - 1L;
        for (int index = 0; index < indices.length; index++) {
            long value = indices[index] & mask;
            long bitIndex = (long) index * bitsPerEntry;
            int wordIndex = (int) (bitIndex >>> 6);
            int bitOffset = (int) (bitIndex & 63L);

            packed[wordIndex] |= value << bitOffset;
            int spillBits = bitOffset + bitsPerEntry - Long.SIZE;
            if (spillBits > 0) {
                packed[wordIndex + 1] |= value >>> (bitsPerEntry - spillBits);
            }
        }
        return packed;
    }

    private static String resolveBiomeKey(Biome biome) {
        if (biome == null) {
            return "minecraft:plains";
        }
        NamespacedKey key = biome.getKey();
        return key != null ? key.asString() : "minecraft:plains";
    }

    private static String resolveBlockEntityTypeKey(Material material) {
        if (material == null || material.getKey() == null) {
            return null;
        }

        String path = material.getKey().getKey();
        if (path.endsWith("_wall_hanging_sign") || path.endsWith("_hanging_sign")) {
            return "minecraft:hanging_sign";
        }
        if (path.endsWith("_wall_sign") || path.endsWith("_sign")) {
            return "minecraft:sign";
        }
        if (path.endsWith("_wall_banner") || path.endsWith("_banner")) {
            return "minecraft:banner";
        }
        if (path.endsWith("_bed")) {
            return "minecraft:bed";
        }
        if (path.endsWith("_wall_head") || path.endsWith("_head") || path.endsWith("_wall_skull") || path.endsWith("_skull")) {
            return "minecraft:skull";
        }
        if (path.endsWith("shulker_box")) {
            return "minecraft:shulker_box";
        }
        if (path.equals("spawner")) {
            return "minecraft:mob_spawner";
        }
        if (path.equals("chain_command_block") || path.equals("command_block") || path.equals("repeating_command_block")) {
            return "minecraft:command_block";
        }
        return material.getKey().asString();
    }

    private static final class IndexedPalette<T> {
        private final Map<T, Integer> indexes = new LinkedHashMap<>();
        private final List<T> values = new ArrayList<>();

        private int indexOf(T value) {
            return indexes.computeIfAbsent(value, ignored -> {
                values.add(value);
                return values.size() - 1;
            });
        }

        private int size() {
            return values.size();
        }

        private List<T> values() {
            return List.copyOf(values);
        }
    }
}