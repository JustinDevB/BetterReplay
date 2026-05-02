package me.justindevb.replay.playback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import me.justindevb.replay.Replay;
import me.justindevb.replay.chunk.CapturedChunkBaseline;
import me.justindevb.replay.chunk.ChunkCoordinate;
import me.justindevb.replay.chunk.ReplayChunkData;
import me.justindevb.replay.chunk.WorldChunkPacketFriendlyCaptureService;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.binary.BinaryChunkCompression;
import me.justindevb.replay.storage.binary.BinaryChunkPayloadCodec;
import me.justindevb.replay.storage.binary.BinaryChunkPayloadFormat;
import me.justindevb.replay.storage.binary.BinaryPacketFriendlyChunkPayloadCodec;
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
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayBlockManagerTest {

    private final BinaryChunkPayloadCodec payloadCodec = new BinaryChunkPayloadCodec();
    private final BinaryPacketFriendlyChunkPayloadCodec packetFriendlyPayloadCodec = new BinaryPacketFriendlyChunkPayloadCodec();
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

    @Test
    void refreshVisibleChunkBaselines_sendsAndRestoresPacketFriendlyChunksViaSenderSeam() throws Exception {
        Player viewer = mock(Player.class);
        Replay replay = mock(Replay.class);
        World world = mock(World.class);
        ReplayChunkSnapshotSender snapshotSender = mock(ReplayChunkSnapshotSender.class);
        WorldChunkPacketFriendlyCaptureService liveChunkCaptureService = mock(WorldChunkPacketFriendlyCaptureService.class);
        ChunkCoordinate chunkCoordinate = new ChunkCoordinate("world", 0, 0);
        byte[] replayPayloadBytes = packetFriendlyPayloadCodec.encode(packetFriendlyPayload());
        byte[] livePayloadBytes = packetFriendlyPayloadCodec.encode(packetFriendlyPayload());

        when(viewer.isOnline()).thenReturn(true);
        when(viewer.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(viewer.getLocation()).thenReturn(new Location(world, 0, 64, 0), new Location(world, 160, 64, 160));
        when(liveChunkCaptureService.capture(chunkCoordinate)).thenReturn(
                new CapturedChunkBaseline(chunkCoordinate, livePayloadBytes, BinaryChunkPayloadFormat.BRCP));

        ReplayBlockManager manager = new ReplayBlockManager(
                viewer,
                replay,
                new ReplayChunkPlaybackCache(replayChunkData(BinaryChunkPayloadFormat.BRCP, replayPayloadBytes)),
                BinaryChunkPayloadFormat.BRCP,
                packetFriendlyPayloadCodec,
                liveChunkCaptureService,
                snapshotSender);

        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            manager.refreshVisibleChunkBaselines();
            manager.refreshVisibleChunkBaselines();
        }

        verify(snapshotSender, times(2)).send(eq(viewer), eq(chunkCoordinate), any(BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload.class));
        verify(liveChunkCaptureService).capture(chunkCoordinate);
    }

    @Test
    void refreshVisibleChunkBaselines_logsSnapshotSendFailures() throws Exception {
        Player viewer = mock(Player.class);
        Replay replay = mock(Replay.class);
        World world = mock(World.class);
        ReplayChunkSnapshotSender snapshotSender = mock(ReplayChunkSnapshotSender.class);
        WorldChunkPacketFriendlyCaptureService liveChunkCaptureService = mock(WorldChunkPacketFriendlyCaptureService.class);
        ChunkCoordinate chunkCoordinate = new ChunkCoordinate("world", 0, 0);
        byte[] replayPayloadBytes = packetFriendlyPayloadBytes(0);
        TestLogHandler logHandler = new TestLogHandler();
        Logger logger = testLogger(logHandler);

        when(viewer.isOnline()).thenReturn(true);
        when(viewer.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(viewer.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        org.mockito.Mockito.doThrow(new IOException("send failed"))
                .when(snapshotSender)
                .send(eq(viewer), eq(chunkCoordinate), any(BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload.class));

        ReplayBlockManager manager = new ReplayBlockManager(
                viewer,
                replay,
                new ReplayChunkPlaybackCache(replayChunkData(BinaryChunkPayloadFormat.BRCP, replayPayloadBytes)),
                BinaryChunkPayloadFormat.BRCP,
                packetFriendlyPayloadCodec,
                liveChunkCaptureService,
                snapshotSender,
                logger);

        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            manager.refreshVisibleChunkBaselines();
        }

        assertTrue(logHandler.contains(Level.WARNING, "Failed to send replay chunk snapshot for ChunkCoordinate[worldName=world, chunkX=0, chunkZ=0]"));
        assertTrue(logHandler.containsThrown(IOException.class, "send failed"));
    }

        @Test
        void refreshVisibleChunkBaselines_reappliesReplayMutationsWhenPacketFriendlyChunkReentersView() throws Exception {
        Player viewer = mock(Player.class);
        Replay replay = mock(Replay.class);
        World world = mock(World.class);
        ReplayChunkSnapshotSender snapshotSender = mock(ReplayChunkSnapshotSender.class);
        WorldChunkPacketFriendlyCaptureService liveChunkCaptureService = mock(WorldChunkPacketFriendlyCaptureService.class);
        BlockData mutationBlockData = mock(BlockData.class);
        PacketEventsAPI<?> packetEventsApi = mock(PacketEventsAPI.class);
        PlayerManager playerManager = mock(PlayerManager.class);
        ServerManager serverManager = mock(ServerManager.class);
        ChunkCoordinate chunkCoordinate = new ChunkCoordinate("world", 0, 0);
        byte[] replayPayloadBytes = packetFriendlyPayloadBytes(0);
        byte[] livePayloadBytes = packetFriendlyPayloadBytes(1);

        when(viewer.isOnline()).thenReturn(true);
        when(viewer.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(viewer.getLocation()).thenReturn(
            new Location(world, 0, 64, 0),
            new Location(world, 160, 64, 160),
            new Location(world, 0, 64, 0));
        when(liveChunkCaptureService.capture(chunkCoordinate)).thenReturn(
            new CapturedChunkBaseline(chunkCoordinate, livePayloadBytes, BinaryChunkPayloadFormat.BRCP));

        ReplayBlockManager manager = new ReplayBlockManager(
            viewer,
            replay,
            new ReplayChunkPlaybackCache(replayChunkData(BinaryChunkPayloadFormat.BRCP, replayPayloadBytes)),
            BinaryChunkPayloadFormat.BRCP,
            packetFriendlyPayloadCodec,
            liveChunkCaptureService,
            snapshotSender);
        manager.configureChunkReplayContext(
            List.of(new TimelineEvent.BlockPlace(3, "u1", "world", 1, 64, 1, "minecraft:gold_block", "minecraft:air")),
            () -> 1);

        when(packetEventsApi.getPlayerManager()).thenReturn(playerManager);
        when(packetEventsApi.getServerManager()).thenReturn(serverManager);
        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_21_11);

        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class);
             MockedStatic<PacketEvents> packetEvents = org.mockito.Mockito.mockStatic(PacketEvents.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(() -> Bukkit.createBlockData("minecraft:gold_block")).thenReturn(mutationBlockData);
            packetEvents.when(PacketEvents::getAPI).thenReturn(packetEventsApi);

            manager.refreshVisibleChunkBaselines();
            manager.refreshVisibleChunkBaselines();
            manager.refreshVisibleChunkBaselines();
        }

        verify(snapshotSender, times(2)).send(
            eq(viewer),
            eq(chunkCoordinate),
            argThat(payload -> payload != null && payload.minSectionY() == 0));
        verify(snapshotSender, times(1)).send(
            eq(viewer),
            eq(chunkCoordinate),
            argThat(payload -> payload != null && payload.minSectionY() == 1));
        verify(liveChunkCaptureService).capture(chunkCoordinate);
        verify(viewer, times(2)).sendBlockChange(eq(new Location(world, 1, 64, 1)), eq(mutationBlockData));
        }

    private ReplayChunkData replayChunkData() throws Exception {
        byte[] payload = payloadCodec.encode(0, 1, List.of("minecraft:stone"), new short[16 * 16]);
        return replayChunkData(BinaryChunkPayloadFormat.BRCS, payload);
    }

    private ReplayChunkData replayChunkData(BinaryChunkPayloadFormat payloadFormat, byte[] payload) throws Exception {
        byte[] compressedPayload = compress(payload);
        byte[] regionBytes = regionCodec.encode(List.of(new BinaryChunkRegionEntry(0, 0, payload.length, BinaryChunkCompression.LZ4_FRAME, compressedPayload)));
        return new ReplayChunkData(
                BinaryReplayChunkMetadata.present(1, 1, "abcd", payloadFormat),
                Map.of("chunks/world/r.0.0.brregion", regionBytes));
    }

    private BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload packetFriendlyPayload() {
        return packetFriendlyPayload(0);
    }

    private BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload packetFriendlyPayload(int minSectionY) {
        return new BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload(
                minSectionY,
                List.of(new BinaryPacketFriendlyChunkPayloadCodec.SectionPayload(
                        List.of("minecraft:air"),
                        0,
                        new long[0],
                        List.of("minecraft:plains"),
                        0,
                        new long[0])),
                List.of());
    }

    private byte[] packetFriendlyPayloadBytes(int minSectionY) {
        return packetFriendlyPayloadCodec.encode(packetFriendlyPayload(minSectionY));
    }

    private static byte[] compress(byte[] payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(out)) {
            lz4.write(payload);
        }
        return out.toByteArray();
    }

    private static Logger testLogger(TestLogHandler handler) {
        Logger logger = Logger.getLogger("ReplayBlockManagerTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        return logger;
    }

    private static final class TestLogHandler extends Handler {
        private final java.util.List<LogRecord> records = new java.util.ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        boolean contains(Level level, String messageFragment) {
            return records.stream().anyMatch(record -> record.getLevel().equals(level)
                    && record.getMessage() != null
                    && record.getMessage().contains(messageFragment));
        }

        boolean containsThrown(Class<? extends Throwable> type, String messageFragment) {
            return records.stream().anyMatch(record -> type.isInstance(record.getThrown())
                    && record.getThrown().getMessage() != null
                    && record.getThrown().getMessage().contains(messageFragment));
        }
    }
}