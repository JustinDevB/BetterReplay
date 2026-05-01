package me.justindevb.replay.chunk;

import me.justindevb.replay.storage.binary.BinaryChunkPayloadCodec;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Captures live world chunks into a palette-compressed baseline payload.
 */
public final class WorldChunkBaselineCaptureService implements ChunkBaselineCaptureService {

    private final BinaryChunkPayloadCodec payloadCodec;

    public WorldChunkBaselineCaptureService(BinaryChunkPayloadCodec payloadCodec) {
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "payloadCodec");
    }

    @Override
    public CapturedChunkBaseline capture(ChunkCoordinate coordinate) throws IOException {
        World world = Bukkit.getWorld(coordinate.worldName());
        if (world == null) {
            throw new IOException("World is not available for chunk capture: " + coordinate.worldName());
        }

        int minY = world.getMinHeight();
        int height = world.getMaxHeight() - minY;
        if (height <= 0) {
            throw new IOException("World height is invalid for chunk capture: " + coordinate.worldName());
        }

        Chunk chunk = world.getChunkAt(coordinate.chunkX(), coordinate.chunkZ());
        Map<String, Integer> paletteIndexes = new LinkedHashMap<>();
        List<String> palette = new ArrayList<>();
        short[] stateIndexes = new short[16 * 16 * height];
        int writeIndex = 0;

        for (int y = minY; y < world.getMaxHeight(); y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    Block block = chunk.getBlock(x, y, z);
                    String blockState = block.getBlockData().getAsString();
                    int paletteIndex = paletteIndexes.computeIfAbsent(blockState, key -> {
                        palette.add(key);
                        return palette.size() - 1;
                    });
                    if (paletteIndex > 0xFFFF) {
                        throw new IllegalStateException("Chunk palette exceeded 65535 entries");
                    }
                    stateIndexes[writeIndex++] = (short) paletteIndex;
                }
            }
        }

        return new CapturedChunkBaseline(coordinate, payloadCodec.encode(minY, height, palette, stateIndexes));
    }
}