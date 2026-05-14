package me.justindevb.replay.playback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.util.Vector3i;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import me.justindevb.replay.chunk.ChunkCoordinate;
import me.justindevb.replay.chunk.ReplayChunkData;
import me.justindevb.replay.chunk.WorldChunkPacketFriendlyCaptureService;
import me.justindevb.replay.config.ReplayConfigSetting;
import me.justindevb.replay.storage.binary.BinaryChunkPayloadCodec;
import me.justindevb.replay.storage.binary.BinaryChunkPayloadFormat;
import me.justindevb.replay.storage.binary.BinaryPacketFriendlyChunkPayloadCodec;
import org.bukkit.*;
import org.bukkit.entity.Player;

import me.justindevb.replay.Replay;
import me.justindevb.replay.recording.TimelineEvent;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.*;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages block state desync/resync during replay playback.
 * Handles priming initial broken block states, applying block changes,
 * and restoring the real world state when the replay stops.
 */
public class ReplayBlockManager {

    private static final int DEFAULT_PLAYBACK_CHUNK_VIEW_RADIUS = 3;
    private static final int DEFAULT_BRCP_CHUNK_SEND_LIMIT_PER_TICK = 1;
    private static final int DEFAULT_BRCP_CHUNK_CLEAR_LIMIT_PER_TICK = 1;
    private static final int REPLAY_LOAD_PROBE_MULTIPLIER = 10;
    private static final Method BUKKIT_GET_CURRENT_TICK_METHOD = resolveBukkitCurrentTickMethod();
    private static final String PREPARE_RESULT_PREPARED = "prepared";
    private static final String PREPARE_RESULT_PREPARED_PACKET_CACHE_HIT = "prepared-packet-cache-hit";
    private static final String PREPARE_RESULT_MISSING_REPLAY_CHUNK = "missing-replay-chunk";
    private static final String PREPARE_RESULT_UNSUPPORTED_PAYLOAD = "unsupported-payload";
    private static final String PREPARE_RESULT_PREPARE_FAILED = "prepare-failed";

    private final Player viewer;
    private final Replay replay;
    private final ReplayChunkPlaybackCache chunkPlaybackCache;
    private final BinaryChunkPayloadFormat chunkPayloadFormat;
    private final BinaryPacketFriendlyChunkPayloadCodec packetFriendlyPayloadCodec;
    private final WorldChunkPacketFriendlyCaptureService liveChunkCaptureService;
    private final ReplayChunkSnapshotSender replayChunkSnapshotSender;
    private final ReplayChunkPacketPreparer replayChunkPacketPreparer;
    private final Executor replayChunkPreparationExecutor;
    private final Function<Player, ClientVersion> clientVersionResolver;
    private final LiveChunkRestoreDrainScheduler liveChunkRestoreDrainScheduler;
    private final PlaybackChunkMode chunkPlaybackMode;
    private final ChunkSentStateResolver chunkSentStateResolver;
    private final int chunkPlaybackRadius;
    private final int maxReplayChunkAppliesPerRefresh;
    private final int maxLiveChunkRestoresPerRefresh;
    private final int maxReplayChunkPreparesInFlight;
    private final int maxLiveChunkRestorePreparesInFlight;
    private final boolean chunkTimingDiagnosticsEnabled;
    private final Logger logger;

    public record BlockKey(String world, int x, int y, int z) {}

    private final Map<BlockKey, String> sessionBaseline = new HashMap<>();
    private final Map<BlockKey, String> chunkBaseline = new HashMap<>();
    private final Map<ChunkCoordinate, Set<BlockKey>> chunkBlocksByCoordinate = new HashMap<>();
    private final Set<ChunkCoordinate> queuedLiveChunkRestores = new LinkedHashSet<>();
    private final Set<ChunkCoordinate> renderedChunks = new HashSet<>();
    private final Set<ChunkCoordinate> residentReplayChunks = new HashSet<>();
    private final Set<BlockKey> visibleBreakStages = new HashSet<>();
    private final Map<ChunkCoordinate, CompletableFuture<PreparedReplayChunk>> pendingReplayChunkPrepares = new ConcurrentHashMap<>();
    private final Map<ChunkCoordinate, PreparedReplayChunk> preparedReplayChunkCache = new ConcurrentHashMap<>();
    private final Map<ChunkCoordinate, CompletableFuture<PreparedReplayChunk>> pendingLiveChunkRestorePrepares = new ConcurrentHashMap<>();
    private final Set<ChunkCoordinate> unavailableReplayChunks = ConcurrentHashMap.newKeySet();
    private WrappedTask liveChunkRestoreDrainTask;
    private int blockBreakMutationEpoch = 0;
    private ChunkCoordinate currentChunkCenter;
    private List<TimelineEvent> replayTimeline = List.of();
    private Map<ChunkCoordinate, List<IndexedReplayMutation>> replayMutationsByChunk = Map.of();
    private IntSupplier currentTimelineIndexSupplier = () -> 0;

    private record IndexedReplayMutation(int index, TimelineEvent event) {
    }

    private record PreparedReplayChunk(PacketFriendlyChunkColumnBuilder.PreparedChunkPacket packet) {
    }

    private record ReplayLoadApplyCounts(int appliedCount, int preparedPacketCacheHits, int freshPreparedLoads) {
    }

    private record LiveChunkRestoreProcessResult(int restoredCount, int capturesStarted, long captureNanos) {
    }

    private enum ReplayLoadApplySource {
        NOT_APPLIED,
        PREPARED_PACKET_CACHE,
        FRESH_PREPARE
    }

    @FunctionalInterface
    interface LiveChunkRestoreDrainScheduler {
        WrappedTask schedule(Runnable task);
    }

    enum PlaybackChunkMode {
        MOVING_WINDOW(1),
        DEFERRED_RESTORE(2);

        private final int configuredValue;

        PlaybackChunkMode(int configuredValue) {
            this.configuredValue = configuredValue;
        }

        static PlaybackChunkMode fromConfiguredValue(int configuredValue) {
            for (PlaybackChunkMode mode : values()) {
                if (mode.configuredValue == configuredValue) {
                    return mode;
                }
            }
            return MOVING_WINDOW;
        }
    }

    @FunctionalInterface
    interface ChunkSentStateResolver {
        boolean isChunkSent(Player viewer, ChunkCoordinate coordinate);
    }

    public ReplayBlockManager(Player viewer, Replay replay, ReplayChunkData chunkData) {
        ReplayChunkData replayChunkData = chunkData != null ? chunkData : ReplayChunkData.NONE;
        BinaryPacketFriendlyChunkPayloadCodec packetFriendlyPayloadCodec = new BinaryPacketFriendlyChunkPayloadCodec();
        Logger logger = replay != null && replay.getLogger() != null
                ? replay.getLogger()
                : Logger.getLogger(ReplayBlockManager.class.getName());
        this.viewer = viewer;
        this.replay = replay;
        this.chunkPlaybackCache = new ReplayChunkPlaybackCache(replayChunkData);
        this.chunkPayloadFormat = replayChunkData.metadata().payloadFormat();
        this.packetFriendlyPayloadCodec = packetFriendlyPayloadCodec;
        this.liveChunkCaptureService = new WorldChunkPacketFriendlyCaptureService(packetFriendlyPayloadCodec);
        PacketFriendlyChunkColumnBuilder chunkColumnBuilder = new PacketFriendlyChunkColumnBuilder();
        this.replayChunkPacketPreparer = chunkColumnBuilder::prepare;
        this.replayChunkPreparationExecutor = replay != null && replay.getFoliaLib() != null
            ? runnable -> replay.getFoliaLib().getScheduler().runAsync(task -> runnable.run())
            : command -> CompletableFuture.runAsync(command);
        this.clientVersionResolver = player -> PacketEvents.getAPI().getPlayerManager().getClientVersion(player);
        this.liveChunkRestoreDrainScheduler = replay != null && replay.getFoliaLib() != null
                ? task -> replay.getFoliaLib().getScheduler().runTimer(task, 1L, 1L)
                : null;
        this.chunkPlaybackMode = replay != null
            && replay.getConfig() != null
            ? PlaybackChunkMode.fromConfiguredValue(ReplayConfigSetting.PLAYBACK_CHUNK_MODE.getInt(replay.getConfig()))
            : PlaybackChunkMode.MOVING_WINDOW;
        this.chunkSentStateResolver = ReplayBlockManager::isChunkSentByViewer;
        this.chunkPlaybackRadius = replay != null
            && replay.getConfig() != null
            ? Math.max(0, ReplayConfigSetting.PLAYBACK_CHUNK_VIEW_RADIUS.getInt(replay.getConfig()))
            : DEFAULT_PLAYBACK_CHUNK_VIEW_RADIUS;
        this.maxReplayChunkAppliesPerRefresh = replay != null
            && replay.getConfig() != null
            ? Math.max(1, ReplayConfigSetting.PLAYBACK_CHUNK_SEND_LIMIT_PER_TICK.getInt(replay.getConfig()))
            : DEFAULT_BRCP_CHUNK_SEND_LIMIT_PER_TICK;
        this.maxLiveChunkRestoresPerRefresh = replay != null
            && replay.getConfig() != null
            ? Math.max(1, ReplayConfigSetting.PLAYBACK_CHUNK_CLEAR_LIMIT_PER_TICK.getInt(replay.getConfig()))
            : DEFAULT_BRCP_CHUNK_CLEAR_LIMIT_PER_TICK;
        this.maxReplayChunkPreparesInFlight = Math.max(
            this.maxReplayChunkAppliesPerRefresh * REPLAY_LOAD_PROBE_MULTIPLIER,
            computeMaxReplayChunkPreparesInFlight(this.chunkPlaybackRadius));
        this.maxLiveChunkRestorePreparesInFlight = Math.max(
            this.maxLiveChunkRestoresPerRefresh,
            computeMaxLiveChunkRestorePreparesInFlight(this.chunkPlaybackRadius));
        this.chunkTimingDiagnosticsEnabled = replay != null
            && replay.getConfig() != null
            && ReplayConfigSetting.PLAYBACK_CHUNK_TIMING_DIAGNOSTICS.getBoolean(replay.getConfig());
        this.logger = logger;
        this.replayChunkSnapshotSender = (player, coordinate, preparedChunk) -> {
            PacketEvents.getAPI().getPlayerManager().sendPacket(
                    player,
                    new WrapperPlayServerChunkData(
                    preparedChunk.column(),
                    preparedChunk.lightData(),
                            false));
        };
    }

    ReplayBlockManager(
            Player viewer,
            Replay replay,
            ReplayChunkPlaybackCache chunkPlaybackCache,
            BinaryChunkPayloadFormat chunkPayloadFormat,
            BinaryPacketFriendlyChunkPayloadCodec packetFriendlyPayloadCodec,
            WorldChunkPacketFriendlyCaptureService liveChunkCaptureService,
            ReplayChunkSnapshotSender replayChunkSnapshotSender
    ) {
        this(
                viewer,
                replay,
                chunkPlaybackCache,
                chunkPayloadFormat,
                packetFriendlyPayloadCodec,
                liveChunkCaptureService,
                replayChunkSnapshotSender,
                new PacketFriendlyChunkColumnBuilder()::prepare,
                Runnable::run,
                player -> ClientVersion.V_1_21_11,
                null,
                1,
                DEFAULT_BRCP_CHUNK_SEND_LIMIT_PER_TICK,
                DEFAULT_BRCP_CHUNK_CLEAR_LIMIT_PER_TICK,
                computeMaxReplayChunkPreparesInFlight(1),
                computeMaxLiveChunkRestorePreparesInFlight(1),
                Logger.getLogger(ReplayBlockManager.class.getName()),
                PlaybackChunkMode.MOVING_WINDOW,
                (player, coordinate) -> true);
    }

    ReplayBlockManager(
            Player viewer,
            Replay replay,
            ReplayChunkPlaybackCache chunkPlaybackCache,
            BinaryChunkPayloadFormat chunkPayloadFormat,
            BinaryPacketFriendlyChunkPayloadCodec packetFriendlyPayloadCodec,
            WorldChunkPacketFriendlyCaptureService liveChunkCaptureService,
            ReplayChunkSnapshotSender replayChunkSnapshotSender,
            Logger logger
        ) {
        this(
            viewer,
            replay,
            chunkPlaybackCache,
            chunkPayloadFormat,
            packetFriendlyPayloadCodec,
            liveChunkCaptureService,
            replayChunkSnapshotSender,
                new PacketFriendlyChunkColumnBuilder()::prepare,
            Runnable::run,
            player -> ClientVersion.V_1_21_11,
            null,
            1,
            DEFAULT_BRCP_CHUNK_SEND_LIMIT_PER_TICK,
            DEFAULT_BRCP_CHUNK_CLEAR_LIMIT_PER_TICK,
            computeMaxReplayChunkPreparesInFlight(1),
            computeMaxLiveChunkRestorePreparesInFlight(1),
            logger,
            PlaybackChunkMode.MOVING_WINDOW,
            (player, coordinate) -> true);
        }

        ReplayBlockManager(
            Player viewer,
            Replay replay,
            ReplayChunkPlaybackCache chunkPlaybackCache,
            BinaryChunkPayloadFormat chunkPayloadFormat,
            BinaryPacketFriendlyChunkPayloadCodec packetFriendlyPayloadCodec,
            WorldChunkPacketFriendlyCaptureService liveChunkCaptureService,
            ReplayChunkSnapshotSender replayChunkSnapshotSender,
            ReplayChunkPacketPreparer replayChunkPacketPreparer,
            Executor replayChunkPreparationExecutor,
            Function<Player, ClientVersion> clientVersionResolver,
            LiveChunkRestoreDrainScheduler liveChunkRestoreDrainScheduler,
            int chunkPlaybackRadius,
                int maxReplayChunkPreparesInFlight,
                int maxLiveChunkRestorePreparesInFlight,
                Logger logger
            ) {
            this(
                viewer,
                replay,
                chunkPlaybackCache,
                chunkPayloadFormat,
                packetFriendlyPayloadCodec,
                liveChunkCaptureService,
                replayChunkSnapshotSender,
                replayChunkPacketPreparer,
                replayChunkPreparationExecutor,
                clientVersionResolver,
                liveChunkRestoreDrainScheduler,
                chunkPlaybackRadius,
                DEFAULT_BRCP_CHUNK_SEND_LIMIT_PER_TICK,
                DEFAULT_BRCP_CHUNK_CLEAR_LIMIT_PER_TICK,
                maxReplayChunkPreparesInFlight,
                maxLiveChunkRestorePreparesInFlight,
                logger,
                PlaybackChunkMode.MOVING_WINDOW,
                (player, coordinate) -> true);
            }

            ReplayBlockManager(
                Player viewer,
                Replay replay,
                ReplayChunkPlaybackCache chunkPlaybackCache,
                BinaryChunkPayloadFormat chunkPayloadFormat,
                BinaryPacketFriendlyChunkPayloadCodec packetFriendlyPayloadCodec,
                WorldChunkPacketFriendlyCaptureService liveChunkCaptureService,
                ReplayChunkSnapshotSender replayChunkSnapshotSender,
                ReplayChunkPacketPreparer replayChunkPacketPreparer,
                Executor replayChunkPreparationExecutor,
                Function<Player, ClientVersion> clientVersionResolver,
                LiveChunkRestoreDrainScheduler liveChunkRestoreDrainScheduler,
                int chunkPlaybackRadius,
            int maxReplayChunkAppliesPerRefresh,
            int maxLiveChunkRestoresPerRefresh,
            int maxReplayChunkPreparesInFlight,
            int maxLiveChunkRestorePreparesInFlight,
            Logger logger
    ) {
        this(
                viewer,
                replay,
                chunkPlaybackCache,
                chunkPayloadFormat,
                packetFriendlyPayloadCodec,
                liveChunkCaptureService,
                replayChunkSnapshotSender,
                replayChunkPacketPreparer,
                replayChunkPreparationExecutor,
                clientVersionResolver,
                liveChunkRestoreDrainScheduler,
                chunkPlaybackRadius,
                maxReplayChunkAppliesPerRefresh,
                maxLiveChunkRestoresPerRefresh,
                maxReplayChunkPreparesInFlight,
                maxLiveChunkRestorePreparesInFlight,
                logger,
                PlaybackChunkMode.MOVING_WINDOW,
                (player, coordinate) -> true);
    }

    ReplayBlockManager(
            Player viewer,
            Replay replay,
            ReplayChunkPlaybackCache chunkPlaybackCache,
            BinaryChunkPayloadFormat chunkPayloadFormat,
            BinaryPacketFriendlyChunkPayloadCodec packetFriendlyPayloadCodec,
            WorldChunkPacketFriendlyCaptureService liveChunkCaptureService,
            ReplayChunkSnapshotSender replayChunkSnapshotSender,
            ReplayChunkPacketPreparer replayChunkPacketPreparer,
            Executor replayChunkPreparationExecutor,
            Function<Player, ClientVersion> clientVersionResolver,
            LiveChunkRestoreDrainScheduler liveChunkRestoreDrainScheduler,
            int chunkPlaybackRadius,
            int maxReplayChunkPreparesInFlight,
            int maxLiveChunkRestorePreparesInFlight,
            Logger logger,
            PlaybackChunkMode chunkPlaybackMode,
            ChunkSentStateResolver chunkSentStateResolver
    ) {
        this(
                viewer,
                replay,
                chunkPlaybackCache,
                chunkPayloadFormat,
                packetFriendlyPayloadCodec,
                liveChunkCaptureService,
                replayChunkSnapshotSender,
                replayChunkPacketPreparer,
                replayChunkPreparationExecutor,
                clientVersionResolver,
                liveChunkRestoreDrainScheduler,
                chunkPlaybackRadius,
                DEFAULT_BRCP_CHUNK_SEND_LIMIT_PER_TICK,
                DEFAULT_BRCP_CHUNK_CLEAR_LIMIT_PER_TICK,
                maxReplayChunkPreparesInFlight,
                maxLiveChunkRestorePreparesInFlight,
                logger,
                chunkPlaybackMode,
                chunkSentStateResolver);
    }

        ReplayBlockManager(
            Player viewer,
            Replay replay,
            ReplayChunkPlaybackCache chunkPlaybackCache,
            BinaryChunkPayloadFormat chunkPayloadFormat,
            BinaryPacketFriendlyChunkPayloadCodec packetFriendlyPayloadCodec,
            WorldChunkPacketFriendlyCaptureService liveChunkCaptureService,
            ReplayChunkSnapshotSender replayChunkSnapshotSender,
            ReplayChunkPacketPreparer replayChunkPacketPreparer,
            Executor replayChunkPreparationExecutor,
            Function<Player, ClientVersion> clientVersionResolver,
            LiveChunkRestoreDrainScheduler liveChunkRestoreDrainScheduler,
            int chunkPlaybackRadius,
            int maxReplayChunkAppliesPerRefresh,
            int maxLiveChunkRestoresPerRefresh,
            int maxReplayChunkPreparesInFlight,
            int maxLiveChunkRestorePreparesInFlight,
                Logger logger,
                PlaybackChunkMode chunkPlaybackMode,
                ChunkSentStateResolver chunkSentStateResolver
    ) {
        this.viewer = viewer;
        this.replay = replay;
        this.chunkPlaybackCache = Objects.requireNonNull(chunkPlaybackCache, "chunkPlaybackCache");
        this.chunkPayloadFormat = Objects.requireNonNull(chunkPayloadFormat, "chunkPayloadFormat");
        this.packetFriendlyPayloadCodec = Objects.requireNonNull(packetFriendlyPayloadCodec, "packetFriendlyPayloadCodec");
        this.liveChunkCaptureService = Objects.requireNonNull(liveChunkCaptureService, "liveChunkCaptureService");
        this.replayChunkSnapshotSender = Objects.requireNonNull(replayChunkSnapshotSender, "replayChunkSnapshotSender");
        this.replayChunkPacketPreparer = Objects.requireNonNull(replayChunkPacketPreparer, "replayChunkPacketPreparer");
        this.replayChunkPreparationExecutor = Objects.requireNonNull(replayChunkPreparationExecutor, "replayChunkPreparationExecutor");
        this.clientVersionResolver = Objects.requireNonNull(clientVersionResolver, "clientVersionResolver");
        this.liveChunkRestoreDrainScheduler = liveChunkRestoreDrainScheduler;
        this.chunkPlaybackMode = Objects.requireNonNull(chunkPlaybackMode, "chunkPlaybackMode");
        this.chunkSentStateResolver = Objects.requireNonNull(chunkSentStateResolver, "chunkSentStateResolver");
        this.chunkPlaybackRadius = Math.max(0, chunkPlaybackRadius);
        this.maxReplayChunkAppliesPerRefresh = Math.max(1, maxReplayChunkAppliesPerRefresh);
        this.maxLiveChunkRestoresPerRefresh = Math.max(1, maxLiveChunkRestoresPerRefresh);
        this.maxReplayChunkPreparesInFlight = Math.max(1, maxReplayChunkPreparesInFlight);
        this.maxLiveChunkRestorePreparesInFlight = Math.max(1, maxLiveChunkRestorePreparesInFlight);
        this.chunkTimingDiagnosticsEnabled = false;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void configureChunkReplayContext(List<TimelineEvent> timeline, IntSupplier currentTimelineIndexSupplier) {
        this.replayTimeline = List.copyOf(Objects.requireNonNull(timeline, "timeline"));
        this.replayMutationsByChunk = indexReplayMutations(this.replayTimeline);
        this.currentTimelineIndexSupplier = Objects.requireNonNull(currentTimelineIndexSupplier, "currentTimelineIndexSupplier");
    }

    public int getEpoch() {
        return blockBreakMutationEpoch;
    }

    public void incrementEpoch() {
        blockBreakMutationEpoch++;
    }

    public void primeInitialBrokenBlockStates(List<TimelineEvent> timeline) {
        Map<BlockKey, TimelineEvent> firstMutationEventByKey = new LinkedHashMap<>();
        String airBlockData = Material.AIR.createBlockData().getAsString();

        for (TimelineEvent event : timeline) {
            if (!(event instanceof TimelineEvent.BlockBreak) && !(event instanceof TimelineEvent.BlockPlace)) {
                continue;
            }

            BlockKey key = blockKeyFromEvent(event);
            if (key == null) {
                continue;
            }

            firstMutationEventByKey.putIfAbsent(key, event);
        }

        for (Map.Entry<BlockKey, TimelineEvent> entry : firstMutationEventByKey.entrySet()) {
            BlockKey key = entry.getKey();
            TimelineEvent event = entry.getValue();

            String worldName;
            int x, y, z;
            switch (event) {
                case TimelineEvent.BlockBreak e -> { worldName = e.world(); x = e.x(); y = e.y(); z = e.z(); }
                case TimelineEvent.BlockPlace e -> { worldName = e.world(); x = e.x(); y = e.y(); z = e.z(); }
                default -> { continue; }
            }

            if (worldName == null) continue;
            World world = Bukkit.getWorld(worldName);
            if (world == null) continue;

            if (event instanceof TimelineEvent.BlockBreak bb) {
                String blockData = bb.blockData();
                if (blockData != null) {
                    sessionBaseline.put(key, blockData);
                    sendBlockStateToViewer(world, x, y, z, blockData);
                } else {
                    sessionBaseline.put(key, world.getBlockAt(x, y, z).getBlockData().getAsString());
                }
                continue;
            }

            if (event instanceof TimelineEvent.BlockPlace bp) {
                String replacedBlockData = bp.replacedBlockData();
                if (replacedBlockData != null) {
                    sessionBaseline.put(key, replacedBlockData);
                    sendBlockStateToViewer(world, x, y, z, replacedBlockData);
                    continue;
                }

                String placedBlockData = bp.blockData();
                if (placedBlockData == null) continue;

                String currentBlockData = world.getBlockAt(x, y, z).getBlockData().getAsString();
                if (!placedBlockData.equals(currentBlockData)) {
                    sessionBaseline.put(key, currentBlockData);
                    continue;
                }

                sessionBaseline.put(key, airBlockData);
                sendBlockStateToViewer(world, x, y, z, airBlockData);
            }
        }
    }

    public List<TimelineEvent> enrichBlockBreakStageTimeline(List<TimelineEvent> timeline) {
        if (timeline == null || timeline.isEmpty()) {
            return timeline;
        }

        Map<BlockKey, Integer> breakStartTicks = new HashMap<>();
        Map<BlockKey, List<Integer>> nativeStageTicks = new HashMap<>();

        for (TimelineEvent event : timeline) {
            if (!(event instanceof TimelineEvent.BlockBreakStage bbs)) {
                continue;
            }

            BlockKey key = new BlockKey(bbs.world(), bbs.x(), bbs.y(), bbs.z());
            nativeStageTicks.computeIfAbsent(key, ignored -> new ArrayList<>()).add(bbs.tick());
        }

        List<TimelineEvent> synthesizedStages = new ArrayList<>();

        for (TimelineEvent event : timeline) {
            int tickValue = event.tick();

            if (event instanceof TimelineEvent.BlockBreakComplete bbc) {
                BlockKey key = new BlockKey(bbc.world(), bbc.x(), bbc.y(), bbc.z());
                breakStartTicks.put(key, tickValue);
                continue;
            }

            if (!(event instanceof TimelineEvent.BlockBreak bb)) {
                continue;
            }

            BlockKey key = new BlockKey(bb.world(), bb.x(), bb.y(), bb.z());

            Integer startTick = breakStartTicks.remove(key);
            if (startTick == null || tickValue - startTick < 4) {
                continue;
            }

            if (hasNativeStagesBetween(nativeStageTicks.get(key), startTick, tickValue)) {
                continue;
            }

            String uuid = bb.uuid();
            int duration = tickValue - startTick;
            for (int stage = 1; stage <= 9; stage++) {
                int stageTick = startTick + (int) Math.floor((stage / 10.0) * duration);
                if (stageTick <= startTick) {
                    continue;
                }
                if (stageTick >= tickValue) {
                    stageTick = tickValue - 1;
                }
                if (stageTick <= startTick) {
                    continue;
                }

                synthesizedStages.add(new TimelineEvent.BlockBreakStage(
                        stageTick, uuid, key.world(), key.x(), key.y(), key.z(), stage
                ));
            }
        }

        if (synthesizedStages.isEmpty()) {
            return timeline;
        }

        List<TimelineEvent> enriched = new ArrayList<>(timeline);
        enriched.addAll(synthesizedStages);
        enriched.sort(Comparator.comparingInt(TimelineEvent::tick));
        return enriched;
    }

    public void applyReplayBlockChange(TimelineEvent event, boolean immediateBreakRemoval) {
        String worldName;
        int x, y, z;
        switch (event) {
            case TimelineEvent.BlockBreak e -> { worldName = e.world(); x = e.x(); y = e.y(); z = e.z(); }
            case TimelineEvent.BlockPlace e -> { worldName = e.world(); x = e.x(); y = e.y(); z = e.z(); }
            default -> { return; }
        }

        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        BlockKey key = new BlockKey(worldName, x, y, z);
        clearVisibleBreakStage(key);

        if (event instanceof TimelineEvent.BlockPlace bp) {
            String blockData = bp.blockData();
            if (blockData != null) {
                sendBlockStateToViewer(world, x, y, z, blockData);
            }
            return;
        }

        // block_break
        TimelineEvent.BlockBreak bb = (TimelineEvent.BlockBreak) event;
        String brokenBlockData = bb.blockData();
        if (brokenBlockData == null) {
            brokenBlockData = sessionBaseline.get(key);
        }

        if (brokenBlockData != null) {
            sendBlockBreakParticles(world, x, y, z, brokenBlockData);
        }

        Location blockLoc = new Location(world, x, y, z);
        if (immediateBreakRemoval) {
            viewer.sendBlockChange(blockLoc, Material.AIR.createBlockData());
            return;
        }

        int mutationEpoch = blockBreakMutationEpoch;
        replay.getFoliaLib().getScheduler().runLater(
                () -> {
                    if (mutationEpoch != blockBreakMutationEpoch) {
                        return;
                    }
                    viewer.sendBlockChange(blockLoc, Material.AIR.createBlockData());
                },
                3L
        );
    }

    public void restoreSessionBaseline() {
        cancelLiveChunkRestoreDrainTask();
        refreshReplayChunkResidencyFromViewer();
        Set<ChunkCoordinate> chunksToRestore = new LinkedHashSet<>(renderedChunks);
        chunksToRestore.addAll(queuedLiveChunkRestores);
        chunksToRestore.addAll(pendingLiveChunkRestorePrepares.keySet());
        queuedLiveChunkRestores.clear();

        if (chunkPayloadFormat == BinaryChunkPayloadFormat.BRCP) {
            cancelPendingReplayChunkPrepares();
            if (liveChunkRestoreDrainScheduler != null && !chunksToRestore.isEmpty()) {
                queuedLiveChunkRestores.addAll(chunksToRestore);
                startLiveChunkRestoreDrain();
            } else {
                for (ChunkCoordinate coordinate : chunksToRestore) {
                    restoreChunkBaseline(coordinate);
                }
            }
        } else {
            for (ChunkCoordinate coordinate : chunksToRestore) {
                restoreChunkBaseline(coordinate);
            }
        }

        for (BlockKey key : sessionBaseline.keySet()) {
            World world = Bukkit.getWorld(key.world());
            if (world == null) {
                continue;
            }
            String realBlockData = world.getBlockAt(key.x(), key.y(), key.z()).getBlockData().getAsString();
            sendBlockStateToViewer(world, key.x(), key.y(), key.z(), realBlockData);
        }
        chunkBaseline.clear();
        chunkBlocksByCoordinate.clear();
        renderedChunks.clear();
        residentReplayChunks.clear();
        preparedReplayChunkCache.clear();
        unavailableReplayChunks.clear();
        currentChunkCenter = null;
    }

    private void startLiveChunkRestoreDrain() {
        if (liveChunkRestoreDrainScheduler == null || liveChunkRestoreDrainTask != null) {
            return;
        }
        liveChunkRestoreDrainTask = liveChunkRestoreDrainScheduler.schedule(this::drainLiveChunkRestoresDuringTeardown);
    }

    private void drainLiveChunkRestoresDuringTeardown() {
        if (viewer == null || !viewer.isOnline() || viewer.getWorld() == null) {
            clearPendingLiveChunkRestores();
            cancelLiveChunkRestoreDrainTask();
            return;
        }

        processQueuedLiveChunkRestores(maxLiveChunkRestoresPerRefresh, maxLiveChunkRestoresPerRefresh);
        if (queuedLiveChunkRestores.isEmpty() && pendingLiveChunkRestorePrepares.isEmpty()) {
            cancelLiveChunkRestoreDrainTask();
        }
    }

    private void clearPendingLiveChunkRestores() {
        for (CompletableFuture<PreparedReplayChunk> pending : pendingLiveChunkRestorePrepares.values()) {
            pending.cancel(false);
        }
        pendingLiveChunkRestorePrepares.clear();
        queuedLiveChunkRestores.clear();
    }

    private void cancelPendingReplayChunkPrepares() {
        for (CompletableFuture<PreparedReplayChunk> pending : pendingReplayChunkPrepares.values()) {
            pending.cancel(false);
        }
        pendingReplayChunkPrepares.clear();
    }

    private void cancelLiveChunkRestoreDrainTask() {
        if (liveChunkRestoreDrainTask == null) {
            return;
        }
        liveChunkRestoreDrainTask.cancel();
        liveChunkRestoreDrainTask = null;
    }

    public void refreshVisibleChunkBaselines() {
        if (viewer == null || !viewer.isOnline() || viewer.getWorld() == null) {
            return;
        }

        long refreshStartedAt = chunkTimingDiagnosticsEnabled ? System.nanoTime() : 0L;
        long unloadQueueNanos = 0L;
        long enqueueLoadNanos = 0L;
        long applyLoadNanos = 0L;
        long restoreNanos = 0L;
        long liveRestoreCaptureNanos = 0L;
        int queuedUnloadCount = 0;
        int appliedLoadCount = 0;
        int restoredChunkCount = 0;
        int preparedPacketCacheHitCount = 0;
        int freshPreparedLoadCount = 0;
        int liveRestoreCapturesStarted = 0;

        Location location = viewer.getLocation();
        ChunkCoordinate center = new ChunkCoordinate(
                viewer.getWorld().getName(),
                Math.floorDiv(location.getBlockX(), 16),
                Math.floorDiv(location.getBlockZ(), 16));
        List<ChunkCoordinate> desiredChunkOrder = desiredChunks(center);
        Set<ChunkCoordinate> desiredChunks = new LinkedHashSet<>(desiredChunkOrder);
        refreshReplayChunkResidencyFromViewer();
        boolean centerChanged = !center.equals(currentChunkCenter);
        if (centerChanged) {
            currentChunkCenter = center;

            long unloadStartedAt = chunkTimingDiagnosticsEnabled ? System.nanoTime() : 0L;
            Set<ChunkCoordinate> toUnload = new HashSet<>();
            if (chunkPayloadFormat == BinaryChunkPayloadFormat.BRCP) {
                if (chunkPlaybackMode == PlaybackChunkMode.MOVING_WINDOW) {
                    toUnload.addAll(residentReplayChunks);
                    toUnload.removeAll(desiredChunks);
                    for (ChunkCoordinate coordinate : toUnload) {
                        queueLiveChunkRestore(coordinate);
                    }
                }
            } else {
                toUnload.addAll(renderedChunks);
                toUnload.removeAll(desiredChunks);
                for (ChunkCoordinate coordinate : toUnload) {
                    restoreChunkBaseline(coordinate);
                }
            }
            queuedUnloadCount = toUnload.size();
            unloadQueueNanos = elapsedNanos(unloadStartedAt);

            cancelStalePreparedChunks(desiredChunks);
        }

        if (chunkPayloadFormat == BinaryChunkPayloadFormat.BRCP) {
            cancelQueuedLiveChunkRestores(desiredChunks);
            long enqueueStartedAt = chunkTimingDiagnosticsEnabled ? System.nanoTime() : 0L;
            enqueuePreparedPacketFriendlyChunkBaselines(desiredChunkOrder);
            enqueueLoadNanos = elapsedNanos(enqueueStartedAt);

            long applyStartedAt = chunkTimingDiagnosticsEnabled ? System.nanoTime() : 0L;
            ReplayLoadApplyCounts loadApplyCounts = applyReadyPreparedPacketFriendlyChunkBaselines(desiredChunkOrder, maxReplayChunkAppliesPerRefresh);
            appliedLoadCount = loadApplyCounts.appliedCount();
            preparedPacketCacheHitCount = loadApplyCounts.preparedPacketCacheHits();
            freshPreparedLoadCount = loadApplyCounts.freshPreparedLoads();
            applyLoadNanos = elapsedNanos(applyStartedAt);

            if (!centerChanged && appliedLoadCount == 0) {
                long restoreStartedAt = chunkTimingDiagnosticsEnabled ? System.nanoTime() : 0L;
                LiveChunkRestoreProcessResult restoreProcessResult = processQueuedLiveChunkRestores(maxLiveChunkRestoresPerRefresh);
                restoredChunkCount = restoreProcessResult.restoredCount();
                liveRestoreCapturesStarted = restoreProcessResult.capturesStarted();
                liveRestoreCaptureNanos = restoreProcessResult.captureNanos();
                restoreNanos = elapsedNanos(restoreStartedAt);
            }

        logChunkRefreshTimings(center, centerChanged, queuedUnloadCount, appliedLoadCount, restoredChunkCount,
            preparedPacketCacheHitCount, freshPreparedLoadCount, liveRestoreCapturesStarted,
                    unloadQueueNanos, enqueueLoadNanos, applyLoadNanos, restoreNanos, liveRestoreCaptureNanos, refreshStartedAt);
            return;
        }

        if (!centerChanged) {
            return;
        }

        for (ChunkCoordinate coordinate : desiredChunkOrder) {
            if (renderedChunks.contains(coordinate)) {
                continue;
            }
            applyChunkBaseline(coordinate);
        }
    }

    private List<ChunkCoordinate> desiredChunks(ChunkCoordinate center) {
        List<ChunkCoordinate> desiredChunks = new ArrayList<>((chunkPlaybackRadius * 2 + 1) * (chunkPlaybackRadius * 2 + 1));
        for (int deltaX = -chunkPlaybackRadius; deltaX <= chunkPlaybackRadius; deltaX++) {
            for (int deltaZ = -chunkPlaybackRadius; deltaZ <= chunkPlaybackRadius; deltaZ++) {
                desiredChunks.add(new ChunkCoordinate(center.worldName(), center.chunkX() + deltaX, center.chunkZ() + deltaZ));
            }
        }
        desiredChunks.sort(chunkPriorityComparator(center));
        return List.copyOf(desiredChunks);
    }

    private Comparator<ChunkCoordinate> chunkPriorityComparator(ChunkCoordinate center) {
        return Comparator
                .comparingInt((ChunkCoordinate coordinate) -> squaredChunkDistance(center, coordinate))
                .thenComparingInt(coordinate -> chebyshevChunkDistance(center, coordinate))
                .thenComparingInt(coordinate -> manhattanChunkDistance(center, coordinate))
                .thenComparingInt(ChunkCoordinate::chunkX)
                .thenComparingInt(ChunkCoordinate::chunkZ);
    }

    private int squaredChunkDistance(ChunkCoordinate center, ChunkCoordinate coordinate) {
        int deltaX = center.chunkX() - coordinate.chunkX();
        int deltaZ = center.chunkZ() - coordinate.chunkZ();
        return deltaX * deltaX + deltaZ * deltaZ;
    }

    private int chebyshevChunkDistance(ChunkCoordinate center, ChunkCoordinate coordinate) {
        return Math.max(
                Math.abs(center.chunkX() - coordinate.chunkX()),
                Math.abs(center.chunkZ() - coordinate.chunkZ()));
    }

    private int manhattanChunkDistance(ChunkCoordinate center, ChunkCoordinate coordinate) {
        return Math.abs(center.chunkX() - coordinate.chunkX())
                + Math.abs(center.chunkZ() - coordinate.chunkZ());
    }

    public void showGlobalBlockBreakStage(TimelineEvent.BlockBreakStage event) {
        String worldName = event.world();
        if (worldName != null && !worldName.equals(viewer.getWorld().getName())) {
            return;
        }

        int x = event.x();
        int y = event.y();
        int z = event.z();
        int stage = event.stage();

        BlockKey key = worldName != null ? new BlockKey(worldName, x, y, z) : null;
        if (stage < 0) {
            if (key != null) {
                visibleBreakStages.remove(key);
            }
            return;
        }

        int animationId = Objects.hash(worldName, x, y, z);
        WrapperPlayServerBlockBreakAnimation breakAnim =
                new WrapperPlayServerBlockBreakAnimation(animationId, new Vector3i(x, y, z), (byte) stage);

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, breakAnim);
        if (key != null) {
            visibleBreakStages.add(key);
        }
    }

    public void clearAllVisibleBreakStages() {
        if (visibleBreakStages.isEmpty()) {
            return;
        }

        Set<BlockKey> staged = new HashSet<>(visibleBreakStages);
        for (BlockKey key : staged) {
            clearVisibleBreakStage(key);
        }
    }

    public void computeAndApplyBlockStateAtIndex(int targetIndexExclusive, List<TimelineEvent> timeline) {
        String airBlockData = Material.AIR.createBlockData().getAsString();
        Map<BlockKey, String> stateAtTarget = new HashMap<>(sessionBaseline);

        int end = Math.max(0, Math.min(targetIndexExclusive, timeline.size()));
        for (int i = 0; i < end; i++) {
            TimelineEvent event = timeline.get(i);
            if (event instanceof TimelineEvent.BlockPlace bp) {
                BlockKey key = new BlockKey(bp.world(), bp.x(), bp.y(), bp.z());
                String bd = bp.blockData();
                if (bd != null) {
                    stateAtTarget.put(key, bd);
                }
            } else if (event instanceof TimelineEvent.BlockBreak bb) {
                BlockKey key = new BlockKey(bb.world(), bb.x(), bb.y(), bb.z());
                stateAtTarget.put(key, airBlockData);
            }
        }

        for (Map.Entry<BlockKey, String> entry : stateAtTarget.entrySet()) {
            BlockKey key = entry.getKey();
            World world = Bukkit.getWorld(key.world());
            if (world != null) {
                sendBlockStateToViewer(world, key.x(), key.y(), key.z(), entry.getValue());
            }
        }
    }

    public void applyReplayBlockChangesInRange(int fromIndex, int toIndexExclusive, List<TimelineEvent> timeline) {
        int start = Math.max(0, Math.min(fromIndex, timeline.size()));
        int end = Math.max(start, Math.min(toIndexExclusive, timeline.size()));

        for (int i = start; i < end; i++) {
            TimelineEvent event = timeline.get(i);
            if (event instanceof TimelineEvent.BlockPlace || event instanceof TimelineEvent.BlockBreak) {
                applyReplayBlockChange(event, true);
            } else if (event instanceof TimelineEvent.BlockBreakStage bbs) {
                showGlobalBlockBreakStage(bbs);
            }
        }
    }

    public void rebuildReplayBlockStateUntil(int targetIndexExclusive, List<TimelineEvent> timeline) {
        clearAllVisibleBreakStages();
        computeAndApplyBlockStateAtIndex(targetIndexExclusive, timeline);
    }

    // -- helpers --

    private void clearVisibleBreakStage(BlockKey key) {
        int animationId = Objects.hash(key.world(), key.x(), key.y(), key.z());
        WrapperPlayServerBlockBreakAnimation clearAnim =
                new WrapperPlayServerBlockBreakAnimation(animationId, new Vector3i(key.x(), key.y(), key.z()), (byte) -1);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, clearAnim);
        visibleBreakStages.remove(key);
    }

    private void sendBlockStateToViewer(World world, int x, int y, int z, String blockData) {
        try {
            viewer.sendBlockChange(new Location(world, x, y, z), Bukkit.createBlockData(blockData));
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void sendBlockBreakParticles(World world, int x, int y, int z, String blockData) {
        try {
            Location center = new Location(world, x + 0.5, y + 0.5, z + 0.5);
            viewer.spawnParticle(
                    Particle.BLOCK,
                    center,
                    24,
                    0.25,
                    0.25,
                    0.25,
                    0.02,
                    Bukkit.createBlockData(blockData)
            );
        } catch (IllegalArgumentException ignored) {
        }
    }

    private boolean hasNativeStagesBetween(List<Integer> stageTicks, int startTick, int endTick) {
        if (stageTicks == null || stageTicks.isEmpty()) {
            return false;
        }
        for (Integer stageTick : stageTicks) {
            if (stageTick != null && stageTick > startTick && stageTick < endTick) {
                return true;
            }
        }
        return false;
    }

    public BlockKey blockKeyFromEvent(TimelineEvent event) {
        return switch (event) {
            case TimelineEvent.BlockBreak e -> new BlockKey(e.world(), e.x(), e.y(), e.z());
            case TimelineEvent.BlockPlace e -> new BlockKey(e.world(), e.x(), e.y(), e.z());
            case TimelineEvent.BlockBreakStage e -> new BlockKey(e.world(), e.x(), e.y(), e.z());
            case TimelineEvent.BlockBreakComplete e -> new BlockKey(e.world(), e.x(), e.y(), e.z());
            default -> null;
        };
    }

    // -- type casting helpers (retained for ReplaySession compatibility) --

    public static Double asDouble(Object obj) {
        return obj instanceof Number n ? n.doubleValue() : null;
    }

    public static Integer asInt(Object obj) {
        return obj instanceof Number n ? n.intValue() : null;
    }

    public static Float asFloat(Object obj) {
        return obj instanceof Number n ? n.floatValue() : 0f;
    }

    public static String asString(Object obj) {
        return obj instanceof String s ? String.valueOf(s) : null;
    }

    private void applyChunkBaseline(ChunkCoordinate coordinate) {
        Optional<ReplayChunkSnapshot> decodedChunk = chunkPlaybackCache.loadChunk(coordinate);
        if (decodedChunk.isEmpty()) {
            return;
        }

        switch (decodedChunk.get()) {
            case ReplayChunkSnapshot.LegacyBlockStateSnapshot legacySnapshot -> applyLegacyChunkBaseline(coordinate, legacySnapshot.payload());
            case ReplayChunkSnapshot.PacketFriendlySnapshot packetFriendlySnapshot -> applyPacketFriendlyChunkSnapshot(coordinate, packetFriendlySnapshot.payload());
        }

        reapplyHistoricalChunkMutations(coordinate);
    }

    private ReplayLoadApplySource applyPreparedPacketFriendlyChunkBaseline(ChunkCoordinate coordinate) {
        if (unavailableReplayChunks.contains(coordinate)) {
            return ReplayLoadApplySource.NOT_APPLIED;
        }

        if (applyCachedPreparedReplayChunk(coordinate)) {
            return renderedChunks.contains(coordinate)
                    ? ReplayLoadApplySource.PREPARED_PACKET_CACHE
                    : ReplayLoadApplySource.NOT_APPLIED;
        }

        CompletableFuture<PreparedReplayChunk> future = pendingReplayChunkPrepares.computeIfAbsent(
                coordinate,
                this::prepareReplayChunkAsync);
        if (!future.isDone()) {
            return ReplayLoadApplySource.NOT_APPLIED;
        }

        pendingReplayChunkPrepares.remove(coordinate, future);
        PreparedReplayChunk preparedChunk;
        try {
            preparedChunk = future.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            logger.log(Level.WARNING, "Failed to prepare replay chunk snapshot for " + coordinate, cause);
            unavailableReplayChunks.add(coordinate);
            return ReplayLoadApplySource.NOT_APPLIED;
        }
        if (preparedChunk == null) {
            unavailableReplayChunks.add(coordinate);
            return ReplayLoadApplySource.NOT_APPLIED;
        }

        applyPreparedReplayChunk(coordinate, preparedChunk);
        return renderedChunks.contains(coordinate)
                ? ReplayLoadApplySource.FRESH_PREPARE
                : ReplayLoadApplySource.NOT_APPLIED;
    }

    private void enqueuePreparedPacketFriendlyChunkBaselines(List<ChunkCoordinate> desiredChunks) {
        releaseCompletedReplayChunkPrepareSlots();
        int availableSlots = Math.max(0, maxReplayChunkPreparesInFlight - pendingReplayChunkPrepares.size());
        if (availableSlots == 0) {
            return;
        }

        for (ChunkCoordinate coordinate : desiredChunks) {
            if (availableSlots == 0) {
                break;
            }
            if (isReplayChunkResident(coordinate)
                    || preparedReplayChunkCache.containsKey(coordinate)
                    || queuedLiveChunkRestores.contains(coordinate)
                    || unavailableReplayChunks.contains(coordinate)
                    || pendingReplayChunkPrepares.containsKey(coordinate)) {
                continue;
            }
            pendingReplayChunkPrepares.put(coordinate, prepareReplayChunkAsync(coordinate));
            availableSlots--;
        }
    }

    private void releaseCompletedReplayChunkPrepareSlots() {
        for (Map.Entry<ChunkCoordinate, CompletableFuture<PreparedReplayChunk>> entry : pendingReplayChunkPrepares.entrySet()) {
            ChunkCoordinate coordinate = entry.getKey();
            CompletableFuture<PreparedReplayChunk> future = entry.getValue();
            if (!future.isDone()) {
                continue;
            }

            if (preparedReplayChunkCache.containsKey(coordinate)) {
                pendingReplayChunkPrepares.remove(coordinate, future);
                continue;
            }

            try {
                PreparedReplayChunk preparedChunk = future.join();
                if (preparedChunk == null) {
                    unavailableReplayChunks.add(coordinate);
                }
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                logger.log(Level.WARNING, "Failed to prepare replay chunk snapshot for " + coordinate, cause);
                unavailableReplayChunks.add(coordinate);
            }

            pendingReplayChunkPrepares.remove(coordinate, future);
        }
    }

    private ReplayLoadApplyCounts applyReadyPreparedPacketFriendlyChunkBaselines(List<ChunkCoordinate> desiredChunks, int maxApplies) {
        int applied = 0;
        int preparedPacketCacheHits = 0;
        int freshPreparedLoads = 0;
        for (ChunkCoordinate coordinate : desiredChunks) {
            if (applied >= maxApplies) {
                break;
            }
            if (isReplayChunkResident(coordinate)) {
                continue;
            }

            CompletableFuture<PreparedReplayChunk> future = pendingReplayChunkPrepares.get(coordinate);
            if (!preparedReplayChunkCache.containsKey(coordinate) && (future == null || !future.isDone())) {
                continue;
            }

            ReplayLoadApplySource applySource = applyPreparedPacketFriendlyChunkBaseline(coordinate);
            switch (applySource) {
                case PREPARED_PACKET_CACHE -> {
                    preparedPacketCacheHits++;
                    applied++;
                }
                case FRESH_PREPARE -> {
                    freshPreparedLoads++;
                    applied++;
                }
                case NOT_APPLIED -> {
                }
            }
        }
        return new ReplayLoadApplyCounts(applied, preparedPacketCacheHits, freshPreparedLoads);
    }

    private CompletableFuture<PreparedReplayChunk> prepareReplayChunkAsync(ChunkCoordinate coordinate) {
        ClientVersion clientVersion = clientVersionResolver.apply(viewer);
        return CompletableFuture.supplyAsync(() -> prepareReplayChunk(coordinate, clientVersion), replayChunkPreparationExecutor);
    }

    private PreparedReplayChunk prepareReplayChunk(ChunkCoordinate coordinate, ClientVersion clientVersion) {
        long prepareStartedAt = chunkTimingDiagnosticsEnabled ? System.nanoTime() : 0L;
        Boolean cacheHit = null;
        try {
            PreparedReplayChunk cachedPreparedChunk = preparedReplayChunkCache.get(coordinate);
            if (cachedPreparedChunk != null) {
                logPreparedChunkTiming(coordinate, elapsedNanos(prepareStartedAt), PREPARE_RESULT_PREPARED_PACKET_CACHE_HIT, null);
                return cachedPreparedChunk;
            }

            ReplayChunkPlaybackCache.ChunkLoadResult chunkLoadResult;
            synchronized (chunkPlaybackCache) {
                chunkLoadResult = chunkPlaybackCache.loadChunkWithDiagnostics(coordinate);
            }
            cacheHit = chunkLoadResult.cacheHit();
            Optional<ReplayChunkSnapshot> decodedChunk = chunkLoadResult.snapshot();
            if (decodedChunk.isEmpty()) {
                logPreparedChunkTiming(coordinate, elapsedNanos(prepareStartedAt), PREPARE_RESULT_MISSING_REPLAY_CHUNK, cacheHit);
                return null;
            }
            if (!(decodedChunk.get() instanceof ReplayChunkSnapshot.PacketFriendlySnapshot packetFriendlySnapshot)) {
                logPreparedChunkTiming(coordinate, elapsedNanos(prepareStartedAt), PREPARE_RESULT_UNSUPPORTED_PAYLOAD, cacheHit);
                return null;
            }

            PreparedReplayChunk preparedChunk = new PreparedReplayChunk(
                    replayChunkPacketPreparer.prepare(coordinate, packetFriendlySnapshot.payload(), clientVersion));
            PreparedReplayChunk reusablePreparedChunk = preparedReplayChunkCache.putIfAbsent(coordinate, preparedChunk);
            if (reusablePreparedChunk == null) {
                reusablePreparedChunk = preparedChunk;
            }
            logPreparedChunkTiming(coordinate, elapsedNanos(prepareStartedAt), PREPARE_RESULT_PREPARED, cacheHit);
            return reusablePreparedChunk;
        } catch (IOException | RuntimeException ex) {
            logPreparedChunkTiming(coordinate, elapsedNanos(prepareStartedAt), PREPARE_RESULT_PREPARE_FAILED, cacheHit);
            throw new CompletionException(ex);
        }
    }

    private boolean applyCachedPreparedReplayChunk(ChunkCoordinate coordinate) {
        PreparedReplayChunk cachedPreparedChunk = preparedReplayChunkCache.get(coordinate);
        if (cachedPreparedChunk == null) {
            return false;
        }

        CompletableFuture<PreparedReplayChunk> pending = pendingReplayChunkPrepares.remove(coordinate);
        if (pending != null) {
            pending.cancel(false);
        }

        applyPreparedReplayChunk(coordinate, cachedPreparedChunk);
        return true;
    }

    private void applyPreparedReplayChunk(ChunkCoordinate coordinate, PreparedReplayChunk preparedChunk) {
        try {
            replayChunkSnapshotSender.send(viewer, coordinate, preparedChunk.packet());
            renderedChunks.add(coordinate);
            residentReplayChunks.add(coordinate);
        } catch (IOException | RuntimeException ex) {
            logger.log(Level.WARNING,
                    "Failed to send replay chunk snapshot for " + coordinate,
                    ex);
        }

        reapplyHistoricalChunkMutations(coordinate);
    }

    private void applyLegacyChunkBaseline(
            ChunkCoordinate coordinate,
            BinaryChunkPayloadCodec.DecodedChunkPayload payload
    ) {
        World world = Bukkit.getWorld(coordinate.worldName());
        if (world == null) {
            return;
        }

        Set<BlockKey> changedBlocks = new HashSet<>();
        short[] stateIndexes = payload.stateIndexes();
        int height = payload.height();
        int index = 0;
        int baseX = coordinate.chunkX() << 4;
        int baseZ = coordinate.chunkZ() << 4;
        for (int yOffset = 0; yOffset < height; yOffset++) {
            int y = payload.minY() + yOffset;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    String replayBlockData = payload.palette().get(stateIndexes[index++] & 0xFFFF);
                    int worldX = baseX + x;
                    int worldZ = baseZ + z;
                    String liveBlockData = world.getBlockAt(worldX, y, worldZ).getBlockData().getAsString();
                    if (replayBlockData.equals(liveBlockData)) {
                        continue;
                    }
                    BlockKey key = new BlockKey(coordinate.worldName(), worldX, y, worldZ);
                    chunkBaseline.putIfAbsent(key, liveBlockData);
                    changedBlocks.add(key);
                    sendBlockStateToViewer(world, worldX, y, worldZ, replayBlockData);
                }
            }
        }

        if (!changedBlocks.isEmpty()) {
            chunkBlocksByCoordinate.put(coordinate, changedBlocks);
            renderedChunks.add(coordinate);
        }
    }

    private void applyPacketFriendlyChunkSnapshot(
            ChunkCoordinate coordinate,
            BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload payload
    ) {
        try {
            replayChunkSnapshotSender.send(
                    viewer,
                    coordinate,
                    replayChunkPacketPreparer.prepare(coordinate, payload, clientVersionResolver.apply(viewer)));
            renderedChunks.add(coordinate);
                    residentReplayChunks.add(coordinate);
        } catch (IOException | RuntimeException ex) {
            logger.log(Level.WARNING,
                    "Failed to send replay chunk snapshot for " + coordinate,
                    ex);
        }
    }

    private void reapplyHistoricalChunkMutations(ChunkCoordinate coordinate) {
        List<IndexedReplayMutation> chunkMutations = replayMutationsByChunk.get(coordinate);
        if (chunkMutations == null || chunkMutations.isEmpty()) {
            return;
        }

        int end = Math.max(0, Math.min(currentTimelineIndexSupplier.getAsInt(), replayTimeline.size()));
        for (IndexedReplayMutation indexedMutation : chunkMutations) {
            if (indexedMutation.index() >= end) {
                break;
            }

            TimelineEvent event = indexedMutation.event();
            if (event instanceof TimelineEvent.BlockPlace blockPlace) {
                applyReplayBlockChange(blockPlace, true);
            } else if (event instanceof TimelineEvent.BlockBreak blockBreak) {
                applyReplayBlockChange(blockBreak, true);
            }
        }
    }

    private Map<ChunkCoordinate, List<IndexedReplayMutation>> indexReplayMutations(List<TimelineEvent> timeline) {
        if (timeline.isEmpty()) {
            return Map.of();
        }

        Map<ChunkCoordinate, List<IndexedReplayMutation>> indexedMutations = new HashMap<>();
        for (int index = 0; index < timeline.size(); index++) {
            TimelineEvent event = timeline.get(index);
            ChunkCoordinate coordinate = switch (event) {
                case TimelineEvent.BlockPlace blockPlace when blockPlace.world() != null ->
                        new ChunkCoordinate(blockPlace.world(), Math.floorDiv(blockPlace.x(), 16), Math.floorDiv(blockPlace.z(), 16));
                case TimelineEvent.BlockBreak blockBreak when blockBreak.world() != null ->
                        new ChunkCoordinate(blockBreak.world(), Math.floorDiv(blockBreak.x(), 16), Math.floorDiv(blockBreak.z(), 16));
                default -> null;
            };
            if (coordinate == null) {
                continue;
            }

            indexedMutations.computeIfAbsent(coordinate, ignored -> new ArrayList<>())
                    .add(new IndexedReplayMutation(index, event));
        }

        Map<ChunkCoordinate, List<IndexedReplayMutation>> immutableIndex = new HashMap<>();
        for (Map.Entry<ChunkCoordinate, List<IndexedReplayMutation>> entry : indexedMutations.entrySet()) {
            immutableIndex.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(immutableIndex);
    }

    private void restoreChunkBaseline(ChunkCoordinate coordinate) {
        if (chunkPayloadFormat == BinaryChunkPayloadFormat.BRCP) {
            if (!shouldRestoreReplayChunk(coordinate)) {
                discardReplayChunk(coordinate);
                return;
            }
            CompletableFuture<PreparedReplayChunk> pendingRestore = pendingLiveChunkRestorePrepares.remove(coordinate);
            if (pendingRestore != null && pendingRestore.isDone()) {
                try {
                    PreparedReplayChunk preparedChunk = pendingRestore.join();
                    if (preparedChunk != null) {
                        replayChunkSnapshotSender.send(viewer, coordinate, preparedChunk.packet());
                    }
                } catch (CompletionException | CancellationException ex) {
                    logger.log(Level.WARNING,
                            "Failed to restore live chunk snapshot for " + coordinate,
                            ex.getCause() != null ? ex.getCause() : ex);
                } catch (IOException | RuntimeException ex) {
                    logger.log(Level.WARNING,
                            "Failed to restore live chunk snapshot for " + coordinate,
                            ex);
                }
            } else if (pendingRestore != null) {
                pendingRestore.cancel(false);
                restoreLiveChunkPacket(coordinate);
            } else {
                restoreLiveChunkPacket(coordinate);
            }
            CompletableFuture<PreparedReplayChunk> pending = pendingReplayChunkPrepares.remove(coordinate);
            if (pending != null) {
                pending.cancel(false);
            }
            queuedLiveChunkRestores.remove(coordinate);
            residentReplayChunks.remove(coordinate);
            renderedChunks.remove(coordinate);
            return;
        }

        Set<BlockKey> changedBlocks = chunkBlocksByCoordinate.remove(coordinate);
        if (changedBlocks == null || changedBlocks.isEmpty()) {
            renderedChunks.remove(coordinate);
            return;
        }

        for (BlockKey key : changedBlocks) {
            String liveBlockData = chunkBaseline.remove(key);
            if (liveBlockData == null) {
                continue;
            }
            World world = Bukkit.getWorld(key.world());
            if (world != null) {
                sendBlockStateToViewer(world, key.x(), key.y(), key.z(), liveBlockData);
            }
        }
        residentReplayChunks.remove(coordinate);
        renderedChunks.remove(coordinate);
    }

    private void restoreLiveChunkPacket(ChunkCoordinate coordinate) {
        try {
            WorldChunkPacketFriendlyCaptureService.CapturedChunkSnapshot capturedSnapshot = liveChunkCaptureService.captureDetachedSnapshot(coordinate);
            BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload payload = liveChunkCaptureService.buildPayload(capturedSnapshot);
            replayChunkSnapshotSender.send(
                    viewer,
                    coordinate,
                    replayChunkPacketPreparer.prepare(coordinate, payload, clientVersionResolver.apply(viewer)));
        } catch (IOException | RuntimeException ex) {
            logger.log(Level.WARNING,
                    "Failed to restore live chunk snapshot for " + coordinate,
                    ex);
        }
    }

    private void queueLiveChunkRestore(ChunkCoordinate coordinate) {
        CompletableFuture<PreparedReplayChunk> pending = pendingReplayChunkPrepares.remove(coordinate);
        if (pending != null) {
            pending.cancel(false);
        }
        queuedLiveChunkRestores.add(coordinate);
    }

    private void cancelQueuedLiveChunkRestores(Set<ChunkCoordinate> desiredChunks) {
        queuedLiveChunkRestores.removeIf(desiredChunks::contains);
        for (ChunkCoordinate coordinate : desiredChunks) {
            CompletableFuture<PreparedReplayChunk> pending = pendingLiveChunkRestorePrepares.remove(coordinate);
            if (pending != null) {
                pending.cancel(false);
            }
        }
    }

    private LiveChunkRestoreProcessResult processQueuedLiveChunkRestores(int maxRestores) {
        return processQueuedLiveChunkRestores(maxRestores, 1);
    }

    private LiveChunkRestoreProcessResult processQueuedLiveChunkRestores(int maxRestores, int maxCaptures) {
        if (queuedLiveChunkRestores.isEmpty() || maxRestores <= 0) {
            return new LiveChunkRestoreProcessResult(0, 0, 0L);
        }

        int restored = 0;
        int capturesStarted = 0;
        long captureNanos = 0L;
        int allowedCaptures = Math.max(0, maxCaptures);
        int availablePrepareSlots = Math.max(0, maxLiveChunkRestorePreparesInFlight - pendingLiveChunkRestorePrepares.size());
        ClientVersion clientVersion = null;
        Iterator<ChunkCoordinate> iterator = queuedLiveChunkRestores.iterator();
        while (iterator.hasNext()) {
            ChunkCoordinate coordinate = iterator.next();
            if (!shouldRestoreReplayChunk(coordinate)) {
                iterator.remove();
                CompletableFuture<PreparedReplayChunk> stalePending = pendingLiveChunkRestorePrepares.remove(coordinate);
                if (stalePending != null) {
                    stalePending.cancel(false);
                }
                residentReplayChunks.remove(coordinate);
                renderedChunks.remove(coordinate);
                continue;
            }
            CompletableFuture<PreparedReplayChunk> pending = pendingLiveChunkRestorePrepares.get(coordinate);
            if (pending == null) {
                if (availablePrepareSlots == 0 || capturesStarted >= allowedCaptures) {
                    continue;
                }
                long captureStartedAt = chunkTimingDiagnosticsEnabled ? System.nanoTime() : 0L;
                try {
                    WorldChunkPacketFriendlyCaptureService.CapturedChunkSnapshot capturedSnapshot = liveChunkCaptureService.captureDetachedSnapshot(coordinate);
                    captureNanos += elapsedNanos(captureStartedAt);
                    if (clientVersion == null) {
                        clientVersion = clientVersionResolver.apply(viewer);
                    }
                    pendingLiveChunkRestorePrepares.put(
                            coordinate,
                            prepareLiveChunkRestoreAsync(coordinate, capturedSnapshot, clientVersion));
                    availablePrepareSlots--;
                    capturesStarted++;
                } catch (IOException | RuntimeException ex) {
                    captureNanos += elapsedNanos(captureStartedAt);
                    iterator.remove();
                    logger.log(Level.WARNING,
                            "Failed to capture live chunk snapshot for " + coordinate,
                            ex);
                }
                continue;
            }
            if (!pending.isDone()) {
                continue;
            }
            if (restored >= maxRestores) {
                continue;
            }

            iterator.remove();
            pendingLiveChunkRestorePrepares.remove(coordinate);
            try {
                PreparedReplayChunk preparedChunk = pending.join();
                if (preparedChunk != null) {
                    replayChunkSnapshotSender.send(viewer, coordinate, preparedChunk.packet());
                    residentReplayChunks.remove(coordinate);
                    renderedChunks.remove(coordinate);
                }
                restored++;
            } catch (CompletionException | CancellationException ex) {
                logger.log(Level.WARNING,
                        "Failed to restore live chunk snapshot for " + coordinate,
                        ex.getCause() != null ? ex.getCause() : ex);
            } catch (IOException | RuntimeException ex) {
                logger.log(Level.WARNING,
                        "Failed to restore live chunk snapshot for " + coordinate,
                        ex);
            }
        }
        return new LiveChunkRestoreProcessResult(restored, capturesStarted, captureNanos);
    }

    private CompletableFuture<PreparedReplayChunk> prepareLiveChunkRestoreAsync(
            ChunkCoordinate coordinate,
            WorldChunkPacketFriendlyCaptureService.CapturedChunkSnapshot capturedSnapshot,
            ClientVersion clientVersion
    ) {
        return CompletableFuture.supplyAsync(
                () -> prepareLiveChunkRestore(coordinate, capturedSnapshot, clientVersion),
                replayChunkPreparationExecutor);
    }

    private PreparedReplayChunk prepareLiveChunkRestore(
            ChunkCoordinate coordinate,
            WorldChunkPacketFriendlyCaptureService.CapturedChunkSnapshot capturedSnapshot,
            ClientVersion clientVersion
    ) {
        long prepareStartedAt = chunkTimingDiagnosticsEnabled ? System.nanoTime() : 0L;
        try {
            BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload payload = liveChunkCaptureService.buildPayload(capturedSnapshot);
            PreparedReplayChunk preparedChunk = new PreparedReplayChunk(
                    replayChunkPacketPreparer.prepare(coordinate, payload, clientVersion));
            logPreparedChunkTiming("live-restore", coordinate, elapsedNanos(prepareStartedAt), PREPARE_RESULT_PREPARED, null);
            return preparedChunk;
        } catch (IOException | RuntimeException ex) {
            logPreparedChunkTiming("live-restore", coordinate, elapsedNanos(prepareStartedAt), PREPARE_RESULT_PREPARE_FAILED, null);
            throw new CompletionException(ex);
        }
    }

    private long elapsedNanos(long startedAt) {
        if (!chunkTimingDiagnosticsEnabled || startedAt == 0L) {
            return 0L;
        }
        return System.nanoTime() - startedAt;
    }

    private void logPreparedChunkTiming(ChunkCoordinate coordinate, long elapsedNanos, String result, Boolean cacheHit) {
        logPreparedChunkTiming("replay-load", coordinate, elapsedNanos, result, cacheHit);
    }

    private void logPreparedChunkTiming(String phase, ChunkCoordinate coordinate, long elapsedNanos, String result, Boolean cacheHit) {
        if (!chunkTimingDiagnosticsEnabled) {
            return;
        }
        String cacheHitLabel = cacheHit == null ? "n/a" : cacheHit.toString();
        String tickLabel = currentServerTickLabel();
        logger.log(Level.INFO,
                String.format(Locale.ROOT,
                "Replay chunk async prepare tick=%s phase=%s %s result=%s cacheHit=%s inFlightReplayLoads=%d inFlightLiveRestores=%d duration=%.3fms",
                tickLabel,
                        phase,
                        coordinate,
                        result,
                        cacheHitLabel,
                pendingReplayChunkPrepares.size(),
                pendingLiveChunkRestorePrepares.size(),
                        elapsedNanos / 1_000_000.0));
    }

    private void logChunkRefreshTimings(
            ChunkCoordinate center,
            boolean centerChanged,
            int queuedUnloadCount,
            int appliedLoadCount,
            int restoredChunkCount,
            int preparedPacketCacheHitCount,
            int freshPreparedLoadCount,
            int liveRestoreCapturesStarted,
            long unloadQueueNanos,
            long enqueueLoadNanos,
            long applyLoadNanos,
            long restoreNanos,
            long liveRestoreCaptureNanos,
            long refreshStartedAt
    ) {
        if (!chunkTimingDiagnosticsEnabled) {
            return;
        }
        if (!centerChanged && appliedLoadCount == 0 && restoredChunkCount == 0 && liveRestoreCapturesStarted == 0) {
            return;
        }

        String tickLabel = currentServerTickLabel();
        logger.log(Level.INFO,
                String.format(Locale.ROOT,
                    "Replay chunk refresh tick=%s center=%s changed=%s loadsApplied=%d restoresApplied=%d unloadsQueued=%d inFlightReplayLoads=%d inFlightLiveRestores=%d queuedRestores=%d preparedPacketCacheHits=%d freshPreparedLoads=%d liveRestoreCapturesStarted=%d durations[queueUnload=%.3fms, enqueueLoads=%.3fms, applyLoads=%.3fms, processRestores=%.3fms, captureLiveRestoreSnapshots=%.3fms, total=%.3fms]",
                        tickLabel,
                        center,
                        centerChanged,
                        appliedLoadCount,
                        restoredChunkCount,
                        queuedUnloadCount,
                        pendingReplayChunkPrepares.size(),
                        pendingLiveChunkRestorePrepares.size(),
                        queuedLiveChunkRestores.size(),
                        preparedPacketCacheHitCount,
                        freshPreparedLoadCount,
                        liveRestoreCapturesStarted,
                        unloadQueueNanos / 1_000_000.0,
                        enqueueLoadNanos / 1_000_000.0,
                        applyLoadNanos / 1_000_000.0,
                        restoreNanos / 1_000_000.0,
                        liveRestoreCaptureNanos / 1_000_000.0,
                        elapsedNanos(refreshStartedAt) / 1_000_000.0));
    }

    private static Method resolveBukkitCurrentTickMethod() {
        try {
            return Bukkit.class.getMethod("getCurrentTick");
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    private static String currentServerTickLabel() {
        if (BUKKIT_GET_CURRENT_TICK_METHOD == null) {
            return "n/a";
        }

        try {
            Object tick = BUKKIT_GET_CURRENT_TICK_METHOD.invoke(null);
            if (tick instanceof Number number) {
                return Long.toString(number.longValue());
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return "n/a";
    }

    private void cancelStalePreparedChunks(Set<ChunkCoordinate> desiredChunks) {
        for (Map.Entry<ChunkCoordinate, CompletableFuture<PreparedReplayChunk>> entry : pendingReplayChunkPrepares.entrySet()) {
            if (desiredChunks.contains(entry.getKey())) {
                continue;
            }

            if (pendingReplayChunkPrepares.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().cancel(false);
            }
        }
    }

    private static int computeMaxReplayChunkPreparesInFlight(int chunkPlaybackRadius) {
        return Math.max(3, Math.min(8, Math.max(1, chunkPlaybackRadius) * 2));
    }

    private static int computeMaxLiveChunkRestorePreparesInFlight(int chunkPlaybackRadius) {
        return Math.max(2, Math.min(4, Math.max(1, chunkPlaybackRadius)));
    }

    private void refreshReplayChunkResidencyFromViewer() {
        if (chunkPayloadFormat != BinaryChunkPayloadFormat.BRCP || residentReplayChunks.isEmpty()) {
            return;
        }

        Iterator<ChunkCoordinate> iterator = residentReplayChunks.iterator();
        while (iterator.hasNext()) {
            ChunkCoordinate coordinate = iterator.next();
            if (!isChunkSentToViewer(coordinate)) {
                iterator.remove();
            }
        }
    }

    private boolean isReplayChunkResident(ChunkCoordinate coordinate) {
        return renderedChunks.contains(coordinate) && residentReplayChunks.contains(coordinate);
    }

    private boolean shouldRestoreReplayChunk(ChunkCoordinate coordinate) {
        return residentReplayChunks.contains(coordinate) || isChunkSentToViewer(coordinate);
    }

    private boolean isChunkSentToViewer(ChunkCoordinate coordinate) {
        if (viewer == null || coordinate == null) {
            return false;
        }

        try {
            return chunkSentStateResolver.isChunkSent(viewer, coordinate);
        } catch (RuntimeException ex) {
            return residentReplayChunks.contains(coordinate);
        }
    }

    private void discardReplayChunk(ChunkCoordinate coordinate) {
        CompletableFuture<PreparedReplayChunk> pendingRestore = pendingLiveChunkRestorePrepares.remove(coordinate);
        if (pendingRestore != null) {
            pendingRestore.cancel(false);
        }

        CompletableFuture<PreparedReplayChunk> pendingReplay = pendingReplayChunkPrepares.remove(coordinate);
        if (pendingReplay != null) {
            pendingReplay.cancel(false);
        }

        queuedLiveChunkRestores.remove(coordinate);
        residentReplayChunks.remove(coordinate);
        renderedChunks.remove(coordinate);
    }

    private static boolean isChunkSentByViewer(Player viewer, ChunkCoordinate coordinate) {
        return viewer.isChunkSent(Chunk.getChunkKey(coordinate.chunkX(), coordinate.chunkZ()));
    }
}
