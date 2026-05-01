package me.justindevb.replay.playback;

import me.justindevb.replay.Replay;
import me.justindevb.replay.chunk.ReplayChunkData;
import me.justindevb.replay.storage.binary.BinaryChunkCompression;
import me.justindevb.replay.storage.binary.BinaryChunkPayloadCodec;
import me.justindevb.replay.storage.binary.BinaryChunkRegionCodec;
import me.justindevb.replay.storage.binary.BinaryChunkRegionEntry;
import me.justindevb.replay.storage.binary.BinaryReplayChunkMetadata;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayBlockManagerTest {

    private final BinaryChunkPayloadCodec payloadCodec = new BinaryChunkPayloadCodec();
    private final BinaryChunkRegionCodec regionCodec = new BinaryChunkRegionCodec();

    @Test
    void refreshVisibleChunkBaselines_restoresRealWorldStateWhenChunkLeavesView() throws Exception {
        Player viewer = mock(Player.class);
        Replay replay = mock(Replay.class);
        World world = mock(World.class);
        Block block = mock(Block.class);
        BlockData liveData = mock(BlockData.class);
        BlockData replayData = mock(BlockData.class);

        when(viewer.isOnline()).thenReturn(true);
        when(viewer.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(viewer.getLocation()).thenReturn(new Location(world, 0, 64, 0), new Location(world, 160, 64, 160));
        when(world.getBlockAt(any(Integer.class), any(Integer.class), any(Integer.class))).thenReturn(block);
        when(block.getBlockData()).thenReturn(liveData);
        when(liveData.getAsString()).thenReturn("minecraft:dirt");

        ReplayBlockManager manager = new ReplayBlockManager(viewer, replay, replayChunkData());

        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(() -> Bukkit.createBlockData("minecraft:stone")).thenReturn(replayData);
            bukkit.when(() -> Bukkit.createBlockData("minecraft:dirt")).thenReturn(liveData);

            manager.refreshVisibleChunkBaselines();
            manager.refreshVisibleChunkBaselines();
        }

        verify(viewer, atLeastOnce()).sendBlockChange(any(Location.class), eq(replayData));
        verify(viewer, atLeastOnce()).sendBlockChange(any(Location.class), eq(liveData));
    }

    private ReplayChunkData replayChunkData() throws Exception {
        byte[] payload = payloadCodec.encode(0, 1, List.of("minecraft:stone"), new short[16 * 16]);
        byte[] compressedPayload = compress(payload);
        byte[] regionBytes = regionCodec.encode(List.of(new BinaryChunkRegionEntry(0, 0, payload.length, BinaryChunkCompression.LZ4_FRAME, compressedPayload)));
        return new ReplayChunkData(
                BinaryReplayChunkMetadata.present(1, 1, "abcd"),
                Map.of("chunks/world/r.0.0.brregion", regionBytes));
    }

    private static byte[] compress(byte[] payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(out)) {
            lz4.write(payload);
        }
        return out.toByteArray();
    }
}