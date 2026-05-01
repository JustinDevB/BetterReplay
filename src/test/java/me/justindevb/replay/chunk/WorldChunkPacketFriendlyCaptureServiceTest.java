package me.justindevb.replay.chunk;

import me.justindevb.replay.storage.binary.BinaryPacketFriendlyChunkPayloadCodec;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorldChunkPacketFriendlyCaptureServiceTest {

    private final BinaryPacketFriendlyChunkPayloadCodec codec = new BinaryPacketFriendlyChunkPayloadCodec();

    @Test
    void capture_includesTileEntitiesWithFallbackEmptyCompoundNbt() throws Exception {
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        Block block = mock(Block.class);
        BlockData blockData = mock(BlockData.class);
        TileState tileState = mock(TileState.class);

        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(16);
        when(world.getChunkAt(0, 0)).thenReturn(chunk);
        when(chunk.getBlock(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(block.getBlockData()).thenReturn(blockData);
        when(blockData.getAsString()).thenReturn("minecraft:air");
        when(world.getBiome(anyInt(), anyInt(), anyInt())).thenReturn(null);
        when(chunk.getTileEntities(false)).thenReturn(new BlockState[] {tileState});
        when(tileState.getType()).thenReturn(Material.CHEST);
        when(tileState.getX()).thenReturn(2);
        when(tileState.getY()).thenReturn(5);
        when(tileState.getZ()).thenReturn(3);

        WorldChunkPacketFriendlyCaptureService service = new WorldChunkPacketFriendlyCaptureService(codec);

        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            CapturedChunkBaseline baseline = service.capture(new ChunkCoordinate("world", 0, 0));
            BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload payload = codec.decode(baseline.payloadBytes());

            assertEquals(1, payload.sections().size());
            assertEquals(1, payload.blockEntities().size());
            assertEquals(2, payload.blockEntities().getFirst().localX());
            assertEquals(5, payload.blockEntities().getFirst().yOffset());
            assertEquals(3, payload.blockEntities().getFirst().localZ());
            assertEquals("minecraft:chest", payload.blockEntities().getFirst().typeKey());
            assertArrayEquals(new byte[] {0x0A, 0x00, 0x00, 0x00}, payload.blockEntities().getFirst().nbtBytes());
            assertNotNull(payload.sections().getFirst().blockPalette());
        }
    }
}