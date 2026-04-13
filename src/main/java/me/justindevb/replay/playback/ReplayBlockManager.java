package me.justindevb.replay.playback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.*;
import org.bukkit.entity.Player;

import me.justindevb.replay.Replay;
import me.justindevb.replay.recording.TimelineEvent;

import java.util.*;

/**
 * Manages block state desync/resync during replay playback.
 * Handles priming initial broken block states, applying block changes,
 * and restoring the real world state when the replay stops.
 */
public class ReplayBlockManager {

    private final Player viewer;
    private final Replay replay;

    public record BlockKey(String world, int x, int y, int z) {}

    private final Map<BlockKey, String> sessionBaseline = new HashMap<>();
    private final Set<BlockKey> visibleBreakStages = new HashSet<>();
    private int blockBreakMutationEpoch = 0;

    public ReplayBlockManager(Player viewer, Replay replay) {
        this.viewer = viewer;
        this.replay = replay;
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
        for (BlockKey key : sessionBaseline.keySet()) {
            World world = Bukkit.getWorld(key.world());
            if (world == null) {
                continue;
            }
            String realBlockData = world.getBlockAt(key.x(), key.y(), key.z()).getBlockData().getAsString();
            sendBlockStateToViewer(world, key.x(), key.y(), key.z(), realBlockData);
        }
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
}
