package me.justindevb.replay.playback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import me.justindevb.replay.Replay;
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
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        PacketFriendlyChunkColumnBuilder.PreparedChunkPacket replayPreparedChunk = preparedChunk();
        ChunkCoordinate chunkCoordinate = new ChunkCoordinate("world", 0, 0);
        byte[] replayPayloadBytes = packetFriendlyPayloadCodec.encode(packetFriendlyPayload());

        when(viewer.isOnline()).thenReturn(true);
        when(viewer.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(viewer.getLocation()).thenReturn(new Location(world, 0, 64, 0), new Location(world, 160, 64, 160));
        when(liveChunkCaptureService.capturePayload(chunkCoordinate)).thenReturn(packetFriendlyPayload());

        ReplayBlockManager manager = new ReplayBlockManager(
                viewer,
                replay,
                new ReplayChunkPlaybackCache(replayChunkData(BinaryChunkPayloadFormat.BRCP, replayPayloadBytes)),
                BinaryChunkPayloadFormat.BRCP,
                packetFriendlyPayloadCodec,
                liveChunkCaptureService,
                snapshotSender,
                (coordinate, payload, clientVersion) -> replayPreparedChunk,
                Runnable::run,
                player -> ClientVersion.V_1_21_11,
                1,
                3,
                2,
                Logger.getLogger("ReplayBlockManagerTest.inline"));

        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            manager.refreshVisibleChunkBaselines();
            manager.refreshVisibleChunkBaselines();
            manager.refreshVisibleChunkBaselines();
            manager.refreshVisibleChunkBaselines();
        }

        verify(snapshotSender, times(2)).send(eq(viewer), eq(chunkCoordinate), eq(replayPreparedChunk));
        verify(liveChunkCaptureService).capturePayload(chunkCoordinate);
    }

    @Test
    void refreshVisibleChunkBaselines_logsSnapshotSendFailures() throws Exception {
        Player viewer = mock(Player.class);
        Replay replay = mock(Replay.class);
        World world = mock(World.class);
        ReplayChunkSnapshotSender snapshotSender = mock(ReplayChunkSnapshotSender.class);
        WorldChunkPacketFriendlyCaptureService liveChunkCaptureService = mock(WorldChunkPacketFriendlyCaptureService.class);
        PacketFriendlyChunkColumnBuilder.PreparedChunkPacket replayPreparedChunk = preparedChunk();
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
            .send(eq(viewer), eq(chunkCoordinate), eq(replayPreparedChunk));

        ReplayBlockManager manager = new ReplayBlockManager(
                viewer,
                replay,
                new ReplayChunkPlaybackCache(replayChunkData(BinaryChunkPayloadFormat.BRCP, replayPayloadBytes)),
                BinaryChunkPayloadFormat.BRCP,
                packetFriendlyPayloadCodec,
                liveChunkCaptureService,
                snapshotSender,
            (coordinate, payload, clientVersion) -> replayPreparedChunk,
            Runnable::run,
            player -> ClientVersion.V_1_21_11,
            1,
            3,
            2,
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
        PacketFriendlyChunkColumnBuilder.PreparedChunkPacket replayPreparedChunk = preparedChunk();
        PacketFriendlyChunkColumnBuilder.PreparedChunkPacket livePreparedChunk = preparedChunk();
        ChunkCoordinate chunkCoordinate = new ChunkCoordinate("world", 0, 0);
        byte[] replayPayloadBytes = packetFriendlyPayloadBytes(0);

        when(viewer.isOnline()).thenReturn(true);
        when(viewer.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(viewer.getLocation()).thenReturn(
            new Location(world, 0, 64, 0),
            new Location(world, 160, 64, 160),
            new Location(world, 0, 64, 0));
        ReplayBlockManager manager = new ReplayBlockManager(
            viewer,
            replay,
            new ReplayChunkPlaybackCache(replayChunkData(BinaryChunkPayloadFormat.BRCP, replayPayloadBytes)),
            BinaryChunkPayloadFormat.BRCP,
            packetFriendlyPayloadCodec,
            liveChunkCaptureService,
            snapshotSender,
            (coordinate, payload, clientVersion) -> payload.minSectionY() == 0 ? replayPreparedChunk : livePreparedChunk,
            Runnable::run,
            player -> ClientVersion.V_1_21_11,
            1,
            3,
            2,
            Logger.getLogger("ReplayBlockManagerTest.inline"));
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
            manager.refreshVisibleChunkBaselines();
        }

        verify(snapshotSender, times(1)).send(
            eq(viewer),
            eq(chunkCoordinate),
            eq(replayPreparedChunk));
        verify(snapshotSender, times(0)).send(
            eq(viewer),
            eq(chunkCoordinate),
            eq(livePreparedChunk));
        verify(liveChunkCaptureService, times(0)).capturePayload(chunkCoordinate);
        verify(viewer, times(1)).sendBlockChange(eq(new Location(world, 1, 64, 1)), eq(mutationBlockData));
        }

    @Test
    void refreshVisibleChunkBaselines_prioritizesCenterChunkFirstWithinPlaybackRadius() throws Exception {
        Player viewer = mock(Player.class);
        Replay replay = mock(Replay.class);
        World world = mock(World.class);
        ReplayChunkSnapshotSender snapshotSender = mock(ReplayChunkSnapshotSender.class);
        WorldChunkPacketFriendlyCaptureService liveChunkCaptureService = mock(WorldChunkPacketFriendlyCaptureService.class);
        PacketFriendlyChunkColumnBuilder.PreparedChunkPacket replayPreparedChunk = preparedChunk();
        ChunkCoordinate centerChunk = new ChunkCoordinate("world", 1, 1);
        byte[] replayPayloadBytes = packetFriendlyPayloadBytes(0);

        when(viewer.isOnline()).thenReturn(true);
        when(viewer.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(viewer.getLocation()).thenReturn(new Location(world, 16, 64, 16));

        ReplayBlockManager manager = new ReplayBlockManager(
                viewer,
                replay,
                new ReplayChunkPlaybackCache(replayChunkData(BinaryChunkPayloadFormat.BRCP, replayPayloadBytes, chunkSquare("world", 1, 1, 1))),
                BinaryChunkPayloadFormat.BRCP,
                packetFriendlyPayloadCodec,
                liveChunkCaptureService,
                snapshotSender,
                (coordinate, payload, clientVersion) -> replayPreparedChunk,
                Runnable::run,
                player -> ClientVersion.V_1_21_11,
                1,
                9,
                2,
                Logger.getLogger("ReplayBlockManagerTest.centerFirst"));

        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            manager.refreshVisibleChunkBaselines();
        }

        verify(snapshotSender).send(eq(viewer), eq(centerChunk), eq(replayPreparedChunk));
        verify(snapshotSender, times(1)).send(eq(viewer), any(ChunkCoordinate.class), eq(replayPreparedChunk));
    }

    @Test
    void refreshVisibleChunkBaselines_limitsInFlightReplayChunkPreparesToConfiguredCap() throws Exception {
        Player viewer = mock(Player.class);
        Replay replay = mock(Replay.class);
        World world = mock(World.class);
        ReplayChunkSnapshotSender snapshotSender = mock(ReplayChunkSnapshotSender.class);
        WorldChunkPacketFriendlyCaptureService liveChunkCaptureService = mock(WorldChunkPacketFriendlyCaptureService.class);
        PacketFriendlyChunkColumnBuilder.PreparedChunkPacket replayPreparedChunk = preparedChunk();
        Executor stalledExecutor = command -> {
        };

        when(viewer.isOnline()).thenReturn(true);
        when(viewer.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(viewer.getLocation()).thenReturn(new Location(world, 0, 64, 0));

        ReplayBlockManager manager = new ReplayBlockManager(
                viewer,
                replay,
                new ReplayChunkPlaybackCache(replayChunkData(BinaryChunkPayloadFormat.BRCP, packetFriendlyPayloadBytes(0), chunkSquare("world", 0, 0, 3))),
                BinaryChunkPayloadFormat.BRCP,
                packetFriendlyPayloadCodec,
                liveChunkCaptureService,
                snapshotSender,
                (coordinate, payload, clientVersion) -> replayPreparedChunk,
                stalledExecutor,
                player -> ClientVersion.V_1_21_11,
                3,
                2,
                2,
                Logger.getLogger("ReplayBlockManagerTest.cap"));

        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            manager.refreshVisibleChunkBaselines();
        }

        assertEquals(2, pendingReplayChunkPrepareCount(manager));
    }

    @Test
    void restoreSessionBaseline_restoresQueuedPacketFriendlyChunkOnlyOnce() throws Exception {
        Player viewer = mock(Player.class);
        Replay replay = mock(Replay.class);
        World world = mock(World.class);
        ReplayChunkSnapshotSender snapshotSender = mock(ReplayChunkSnapshotSender.class);
        WorldChunkPacketFriendlyCaptureService liveChunkCaptureService = mock(WorldChunkPacketFriendlyCaptureService.class);
        PacketFriendlyChunkColumnBuilder.PreparedChunkPacket replayPreparedChunk = preparedChunk();
        ChunkCoordinate chunkCoordinate = new ChunkCoordinate("world", 0, 0);

        when(viewer.isOnline()).thenReturn(true);
        when(viewer.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(viewer.getLocation()).thenReturn(
                new Location(world, 0, 64, 0),
                new Location(world, 16, 64, 0));
        when(liveChunkCaptureService.capturePayload(chunkCoordinate)).thenReturn(packetFriendlyPayload(1));

        ReplayBlockManager manager = new ReplayBlockManager(
                viewer,
                replay,
                new ReplayChunkPlaybackCache(replayChunkData(BinaryChunkPayloadFormat.BRCP, packetFriendlyPayloadBytes(0), List.of(chunkCoordinate))),
                BinaryChunkPayloadFormat.BRCP,
                packetFriendlyPayloadCodec,
                liveChunkCaptureService,
                snapshotSender,
                (coordinate, payload, clientVersion) -> replayPreparedChunk,
                Runnable::run,
                player -> ClientVersion.V_1_21_11,
                0,
                2,
                2,
                Logger.getLogger("ReplayBlockManagerTest.stopDedup"));

        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            manager.refreshVisibleChunkBaselines();
            manager.refreshVisibleChunkBaselines();
            manager.restoreSessionBaseline();
        }

        verify(snapshotSender, times(2)).send(eq(viewer), eq(chunkCoordinate), eq(replayPreparedChunk));
        verify(liveChunkCaptureService, times(1)).capturePayload(chunkCoordinate);
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

    private ReplayChunkData replayChunkData(BinaryChunkPayloadFormat payloadFormat, byte[] payload, List<ChunkCoordinate> coordinates) throws Exception {
        byte[] compressedPayload = compress(payload);
        Map<String, List<BinaryChunkRegionEntry>> regions = new LinkedHashMap<>();
        for (ChunkCoordinate coordinate : coordinates) {
            int regionX = Math.floorDiv(coordinate.chunkX(), 32);
            int regionZ = Math.floorDiv(coordinate.chunkZ(), 32);
            String regionPath = "chunks/" + coordinate.worldName() + "/r." + regionX + "." + regionZ + ".brregion";
            regions.computeIfAbsent(regionPath, ignored -> new ArrayList<>())
                    .add(new BinaryChunkRegionEntry(
                            Math.floorMod(coordinate.chunkX(), 32),
                            Math.floorMod(coordinate.chunkZ(), 32),
                            payload.length,
                            BinaryChunkCompression.LZ4_FRAME,
                            compressedPayload));
        }

        Map<String, byte[]> regionEntries = new LinkedHashMap<>();
        for (Map.Entry<String, List<BinaryChunkRegionEntry>> entry : regions.entrySet()) {
            regionEntries.put(entry.getKey(), regionCodec.encode(entry.getValue()));
        }

        return new ReplayChunkData(
            BinaryReplayChunkMetadata.present(regionEntries.size(), coordinates.size(), "abcd", payloadFormat),
                Map.copyOf(regionEntries));
    }

    private List<ChunkCoordinate> chunkSquare(String worldName, int centerChunkX, int centerChunkZ, int radius) {
        List<ChunkCoordinate> coordinates = new ArrayList<>();
        for (int deltaX = -radius; deltaX <= radius; deltaX++) {
            for (int deltaZ = -radius; deltaZ <= radius; deltaZ++) {
                coordinates.add(new ChunkCoordinate(worldName, centerChunkX + deltaX, centerChunkZ + deltaZ));
            }
        }
        return List.copyOf(coordinates);
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

    private static PacketFriendlyChunkColumnBuilder.PreparedChunkPacket preparedChunk() {
        return new PacketFriendlyChunkColumnBuilder.PreparedChunkPacket(mock(Column.class), mock(LightData.class));
    }

    @SuppressWarnings("unchecked")
    private static int pendingReplayChunkPrepareCount(ReplayBlockManager manager) throws Exception {
        Field field = ReplayBlockManager.class.getDeclaredField("pendingReplayChunkPrepares");
        field.setAccessible(true);
        return ((Map<ChunkCoordinate, ?>) field.get(manager)).size();
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