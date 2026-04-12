package me.justindevb.replay.playback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.*;
import org.bukkit.entity.Player;

import me.justindevb.replay.Replay;

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

    public void primeInitialBrokenBlockStates(List<Map<String, Object>> timeline) {
        Map<BlockKey, Map<String, Object>> firstMutationEventByKey = new LinkedHashMap<>();
        String airBlockData = Material.AIR.createBlockData().getAsString();

        for (Map<String, Object> event : timeline) {
            String type = asString(event.get("type"));
            if (!"block_break".equals(type) && !"block_place".equals(type)) {
                continue;
            }

            BlockKey key = blockKeyFromEvent(event);
            if (key == null) {
                continue;
            }

            firstMutationEventByKey.putIfAbsent(key, event);
        }

        for (Map.Entry<BlockKey, Map<String, Object>> entry : firstMutationEventByKey.entrySet()) {
            BlockKey key = entry.getKey();
            Map<String, Object> event = entry.getValue();
            String type = asString(event.get("type"));

            if (type == null) {
                continue;
            }

            String worldName = asString(event.get("world"));
            Integer x = asInt(event.get("x"));
            Integer y = asInt(event.get("y"));
            Integer z = asInt(event.get("z"));

            if (worldName == null || x == null || y == null || z == null) {
                continue;
            }

            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                continue;
            }

            if ("block_break".equals(type)) {
                String blockData = asString(event.get("blockData"));
                if (blockData != null) {
                    sessionBaseline.put(key, blockData);
                } else {
                    sessionBaseline.put(key, world.getBlockAt(x, y, z).getBlockData().getAsString());
                }

                if (blockData != null) {
                    sendBlockStateToViewer(world, x, y, z, blockData);
                }
                continue;
            }

            String replacedBlockData = asString(event.get("replacedBlockData"));
            if (replacedBlockData != null) {
                sessionBaseline.put(key, replacedBlockData);
                sendBlockStateToViewer(world, x, y, z, replacedBlockData);
                continue;
            }

            String placedBlockData = asString(event.get("blockData"));
            if (placedBlockData == null) {
                placedBlockData = asString(event.get("block"));
            }

            if (placedBlockData == null) {
                continue;
            }

            String currentBlockData = world.getBlockAt(x, y, z).getBlockData().getAsString();
            if (!placedBlockData.equals(currentBlockData)) {
                sessionBaseline.put(key, currentBlockData);
                continue;
            }

            sessionBaseline.put(key, airBlockData);
            sendBlockStateToViewer(world, x, y, z, airBlockData);
        }
    }

    public List<Map<String, Object>> enrichBlockBreakStageTimeline(List<Map<String, Object>> timeline) {
        if (timeline == null || timeline.isEmpty()) {
            return timeline;
        }

        Map<BlockKey, Integer> breakStartTicks = new HashMap<>();
        Map<BlockKey, List<Integer>> nativeStageTicks = new HashMap<>();

        for (Map<String, Object> event : timeline) {
            if (!"block_break_stage".equals(event.get("type"))) {
                continue;
            }

            BlockKey key = blockKeyFromEvent(event);
            Integer tickValue = asInt(event.get("tick"));
            if (key == null || tickValue == null) {
                continue;
            }

            nativeStageTicks.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tickValue);
        }

        List<Map<String, Object>> synthesizedStages = new ArrayList<>();

        for (Map<String, Object> event : timeline) {
            String type = asString(event.get("type"));
            Integer tickValue = asInt(event.get("tick"));
            if (type == null || tickValue == null) {
                continue;
            }

            if ("block_break_complete".equals(type) || "block_break_start".equals(type)) {
                BlockKey key = blockKeyFromEvent(event);
                if (key != null) {
                    breakStartTicks.put(key, tickValue);
                }
                continue;
            }

            if (!"block_break".equals(type)) {
                continue;
            }

            BlockKey key = blockKeyFromEvent(event);
            if (key == null) {
                continue;
            }

            Integer startTick = breakStartTicks.remove(key);
            if (startTick == null || tickValue - startTick < 4) {
                continue;
            }

            if (hasNativeStagesBetween(nativeStageTicks.get(key), startTick, tickValue)) {
                continue;
            }

            String uuid = asString(event.get("uuid"));
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

                Map<String, Object> stageEvent = new HashMap<>();
                stageEvent.put("tick", stageTick);
                stageEvent.put("type", "block_break_stage");
                stageEvent.put("world", key.world());
                stageEvent.put("x", key.x());
                stageEvent.put("y", key.y());
                stageEvent.put("z", key.z());
                stageEvent.put("stage", stage);
                if (uuid != null) {
                    stageEvent.put("uuid", uuid);
                }
                synthesizedStages.add(stageEvent);
            }
        }

        if (synthesizedStages.isEmpty()) {
            return timeline;
        }

        List<Map<String, Object>> enriched = new ArrayList<>(timeline);
        enriched.addAll(synthesizedStages);
        enriched.sort(Comparator.comparingInt(event -> {
            Integer tickValue = asInt(event.get("tick"));
            return tickValue != null ? tickValue : Integer.MAX_VALUE;
        }));
        return enriched;
    }

    public void applyReplayBlockChange(Map<String, Object> event, String type, boolean immediateBreakRemoval) {
        String worldName = asString(event.get("world"));
        Integer x = asInt(event.get("x"));
        Integer y = asInt(event.get("y"));
        Integer z = asInt(event.get("z"));

        if (worldName == null || x == null || y == null || z == null) {
            return;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return;
        }

        BlockKey key = new BlockKey(worldName, x, y, z);
        clearVisibleBreakStage(key);

        if ("block_place".equals(type)) {
            String blockData = asString(event.get("blockData"));
            if (blockData == null) {
                blockData = asString(event.get("block"));
            }

            if (blockData != null) {
                sendBlockStateToViewer(world, x, y, z, blockData);
            }
            return;
        }

        String brokenBlockData = asString(event.get("blockData"));
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

    public void showGlobalBlockBreakStage(Map<String, Object> event) {
        String worldName = asString(event.get("world"));
        if (worldName != null && !worldName.equals(viewer.getWorld().getName())) {
            return;
        }

        Integer x = asInt(event.get("x"));
        Integer y = asInt(event.get("y"));
        Integer z = asInt(event.get("z"));
        Integer stage = asInt(event.get("stage"));

        if (x == null || y == null || z == null || stage == null) {
            return;
        }

        BlockKey key = worldName != null ? new BlockKey(worldName, x, y, z) : null;
        if (stage < 0) {
            if (key != null) {
                visibleBreakStages.remove(key);
            }
            return;
        }

        int animationId = Objects.hash(worldName, x, y, z);
        WrapperPlayServerBlockBreakAnimation breakAnim =
                new WrapperPlayServerBlockBreakAnimation(animationId, new Vector3i(x, y, z), stage.byteValue());

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

    public void computeAndApplyBlockStateAtIndex(int targetIndexExclusive, List<Map<String, Object>> timeline) {
        String airBlockData = Material.AIR.createBlockData().getAsString();
        Map<BlockKey, String> stateAtTarget = new HashMap<>(sessionBaseline);

        int end = Math.max(0, Math.min(targetIndexExclusive, timeline.size()));
        for (int i = 0; i < end; i++) {
            Map<String, Object> event = timeline.get(i);
            String type = asString(event.get("type"));
            if ("block_place".equals(type)) {
                BlockKey key = blockKeyFromEvent(event);
                String bd = asString(event.get("blockData"));
                if (bd == null) bd = asString(event.get("block"));
                if (key != null && bd != null) {
                    stateAtTarget.put(key, bd);
                }
            } else if ("block_break".equals(type)) {
                BlockKey key = blockKeyFromEvent(event);
                if (key != null) {
                    stateAtTarget.put(key, airBlockData);
                }
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

    public void applyReplayBlockChangesInRange(int fromIndex, int toIndexExclusive, List<Map<String, Object>> timeline) {
        int start = Math.max(0, Math.min(fromIndex, timeline.size()));
        int end = Math.max(start, Math.min(toIndexExclusive, timeline.size()));

        for (int i = start; i < end; i++) {
            Map<String, Object> event = timeline.get(i);
            String type = asString(event.get("type"));
            if ("block_place".equals(type) || "block_break".equals(type)) {
                applyReplayBlockChange(event, type, true);
            } else if ("block_break_stage".equals(type)) {
                showGlobalBlockBreakStage(event);
            }
        }
    }

    public void rebuildReplayBlockStateUntil(int targetIndexExclusive, List<Map<String, Object>> timeline) {
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

    public BlockKey blockKeyFromEvent(Map<String, Object> event) {
        String worldName = asString(event.get("world"));
        Integer x = asInt(event.get("x"));
        Integer y = asInt(event.get("y"));
        Integer z = asInt(event.get("z"));

        if (worldName == null || x == null || y == null || z == null) {
            return null;
        }

        return new BlockKey(worldName, x, y, z);
    }

    // -- type casting helpers --

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
