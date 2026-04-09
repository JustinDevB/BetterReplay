package me.justindevb.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import com.github.retrooper.packetevents.util.Vector3i;
import me.justindevb.replay.api.events.ReplayStartEvent;
import me.justindevb.replay.api.events.ReplayStopEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import java.util.*;

import static me.justindevb.replay.util.ItemStackSerializer.deserializeItem;

public class ReplaySession implements Listener, PacketListener {
    private final Player viewer;
    private final Replay replay;

    private WrappedTask replayTask = null;

    private List<Map<String, Object>> timeline;
    private final Set<Integer> trackedEntityIds = new HashSet<>();
    private final Set<UUID> deadEntities = new HashSet<>();
    private final Map<UUID, RecordedEntity> recordedEntities = new HashMap<>();
    private final Map<BlockKey, String> sessionBaseline = new HashMap<>();
    private final Set<BlockKey> visibleBreakStages = new HashSet<>();
    private int blockBreakMutationEpoch = 0;
    private int tick = 0;
    private boolean paused = false;
    private ItemStack[] viewerInventory;
    private ItemStack[] viewerArmor;
    private ItemStack viewerOffHand;

    private record BlockKey(String world, int x, int y, int z) {}

    public ReplaySession(List<Map<String, Object>> timeline, Player viewer, Replay replay) {
        this.timeline = timeline;
        this.viewer = viewer;
        this.replay = replay;
        Bukkit.getPluginManager().registerEvents(this, replay);
    }

    public void start() {
        if (timeline == null || timeline.isEmpty()) {
            viewer.sendMessage("Replay is empty!");
            return;
        }

        ReplayRegistry.add(this);
        enrichBlockBreakStageTimeline();
        copyInventory();

        Map<String, Object> firstLocationEvent = timeline.stream()
                .filter(e -> e.containsKey("world") && e.containsKey("x") && e.containsKey("y") && e.containsKey("z"))
                .findFirst()
                .orElse(null);

        if (firstLocationEvent != null) {
            String sWorld = asString(firstLocationEvent.get("world"));
            World world = Bukkit.getWorld(sWorld);
            Double x = asDouble(firstLocationEvent.get("x"));
            Double y = asDouble(firstLocationEvent.get("y"));
            Double z = asDouble(firstLocationEvent.get("z"));
            Float yaw = asFloat(firstLocationEvent.get("yaw"));
            Float pitch = asFloat(firstLocationEvent.get("pitch"));

            if (x != null && y != null && z != null) {
                replay.getFoliaLib().getScheduler().teleportAsync(viewer, new Location(world, x, y, z, yaw, pitch));
            }
        }

        giveReplayControls(viewer);
        primeInitialBrokenBlockStates();

        Bukkit.getPluginManager().callEvent(new ReplayStartEvent(viewer, this));

        replay.getFoliaLib().getScheduler().runTimer(task -> {
            if (paused) {
                sendActionBar();
                return;
            }
            replayTask = task;


            if (tick >= timeline.size()) {
                task.cancel();
                stop();
                return;
            }

            if (viewer == null || !viewer.isOnline()) {
                task.cancel();
                recordedEntities.values().forEach(RecordedEntity::destroy);
                recordedEntities.clear();
                return;
            }

            Map<String, Object> firstEvent = timeline.get(tick);
            Object tickObj = firstEvent.get("tick");

            if (!(tickObj instanceof Number)) {
                tick++;
                return;
            }

            int recordedTick = ((Number) tickObj).intValue();

            while (tick < timeline.size()) {
                Map<String, Object> event = timeline.get(tick);

                Object eventTickObj = event.get("tick");
                if (!(eventTickObj instanceof Number)) break;

                int eventTick = ((Number) eventTickObj).intValue();
                if (eventTick != recordedTick) break;

                String type = (String) event.get("type");
                if (type == null) {
                    tick++;
                    continue;
                }

                if ("block_break_stage".equals(type)) {
                    showGlobalBlockBreakStage(event);
                    tick++;
                    continue;
                }

                Object uuidObj = event.get("uuid");
                if (!(uuidObj instanceof String uuidStr)) {
                    tick++;
                    continue;
                }

                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidStr);
                } catch (IllegalArgumentException ex) {
                    tick++;
                    continue;
                }

                if ("player_quit".equals(type)) {
                    if (recordedEntities.get(uuid) instanceof RecordedPlayer rp) {
                        viewer.sendMessage("[BetterReplay] " + rp.getName() + " disconnected");
                    }
                    RecordedEntity entity = recordedEntities.remove(uuid);
                    if (entity != null) {
                        entity.destroy();
                        trackedEntityIds.remove(entity.getFakeEntityId());
                    }
                    tick++;
                    continue;
                }

                if (deadEntities.contains(uuid) && ("player_move".equals(type) || "entity_move".equals(type))) {
                    tick++;
                    continue;
                }

                RecordedEntity recorded = recordedEntities.get(uuid);

                if (recorded != null && recorded.isDestroyed()) {
                    recordedEntities.remove(uuid);
                    tick++;
                    continue;
                }

                if (recorded == null) {
                    Double x = asDouble(event.get("x"));
                    Double y = asDouble(event.get("y"));
                    Double z = asDouble(event.get("z"));

                    String worldName = asString(event.get("world"));
                    if (x == null || y == null || z == null || worldName == null) {
                        tick++;
                        continue;
                    }

                    World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        tick++;
                        continue;
                    }

                    Location initialLoc = new Location(
                            world,
                            x,
                            y,
                            z,
                            asFloat(event.get("yaw")),
                            asFloat(event.get("pitch"))
                    );

                    recorded = RecordedEntityFactory.create(event, viewer);
                    if (recorded == null) {
                        tick++;
                        continue;
                    }

                    recorded.spawn(initialLoc);
                    recordedEntities.put(uuid, recorded);

                    if (recorded instanceof RecordedPlayer rp) {
                        Map<String, Object> inv = getInventorySnapshotForPlayer(uuid);
                        if (inv != null) rp.updateInventory(inv);
                    }
                }

                handleEvent(recorded, event);

                tick++;
            }
            sendActionBar();

        },1L, 1L);
    }


    public void stop() {
        viewer.sendActionBar(Component.empty());

        Bukkit.getPluginManager().callEvent(new ReplayStopEvent(viewer, this));
        recordedEntities.values().forEach(RecordedEntity::destroy);
        recordedEntities.clear();

        clearFakeItems();
        blockBreakMutationEpoch++;
        clearAllVisibleBreakStages();
        restoreSessionBaseline();
        restoreInventory();
        if (replayTask != null) {
            replay.getFoliaLib().getScheduler().cancelTask(replayTask);
            replayTask = null;
        }

        viewer.sendMessage("Replay finished");
        ReplayRegistry.remove(this);
        HandlerList.unregisterAll(this);
    }

    private void copyInventory() {
        this.viewerInventory = viewer.getInventory().getContents().clone();
        this.viewerArmor = viewer.getInventory().getArmorContents().clone();
        this.viewerOffHand = viewer.getInventory().getItemInOffHand().clone();
        viewer.getInventory().clear();
    }

    private void restoreInventory() {
        viewer.getInventory().clear();
        viewer.getInventory().setContents(viewerInventory);
        viewer.getInventory().setArmorContents(viewerArmor);
        viewer.getInventory().setItemInOffHand(viewerOffHand);
        viewer.updateInventory();
    }

    private void clearFakeItems() {
        for (int id : trackedEntityIds) {
            WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(id);
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, destroy);
        }
        trackedEntityIds.clear();
    }

    private void handleEvent(RecordedEntity entity, Map<String, Object> event) {
        String type = (String) event.get("type");
        if (type == null) return;

        switch (type) {
            case "player_move", "entity_move" -> {
                World world = Bukkit.getWorld(asString(event.get("world")));
                Double x = asDouble(event.get("x"));
                Double y = asDouble(event.get("y"));
                Double z = asDouble(event.get("z"));
                if (x == null || y == null || z == null) return;

                Location loc = new Location(world, x, y, z,
                        asFloat(event.get("yaw")), asFloat(event.get("pitch")));
                entity.moveTo(loc);

                if (event.containsKey("pose") && entity instanceof RecordedPlayer rp) {
                    String poseName = (String) event.get("pose");
                    if (poseName != null) {
                        try {
                            Pose pose = Pose.valueOf(poseName);
                            rp.setPose(pose);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            }
            case "sneak_start", "sneak_stop", "attack", "block_place", "block_break" -> {
                if (entity instanceof RecordedPlayer rp) {
                    switch (type) {
                        case "sneak_start", "sneak_stop" -> rp.updateSneak(type.equals("sneak_start"));
                        case "attack" -> rp.playAttackAnimation();
                        case "block_place" -> rp.showBlockPlace(event);
                        case "block_break" -> rp.showBlockBreak(event);
                    }
                }

                if ("block_place".equals(type) || "block_break".equals(type)) {
                    applyReplayBlockChange(event, type, false);
                }
            }
            case "block_break_stage" -> {
                if (entity instanceof RecordedPlayer rp) {
                    rp.showBlockBreak(event);
                }
            }
            case "swing" -> {
                if (entity instanceof RecordedPlayer rp) {
                    String hand = (String) event.get("hand");
                    rp.playSwing(hand);
                }
            }

            case "damaged" -> {
                entity.showDamage(event);
            }
            case "sprint_start", "sprint_stop" -> {
                if (entity instanceof RecordedPlayer rp) {
                    rp.updateSprint(type.equals("sprint_start"));
                }
            }
            case "entity_death" -> {
                entity.showDeath(event);
                deadEntities.add(entity.uuid);
                entity.destroy();
                recordedEntities.remove(entity.uuid);
            }
            case "inventory_update" -> {
                if (entity instanceof RecordedPlayer rp) {
                    rp.updateInventory(event);
                }
            }
            case "item_drop" -> {
              //  Map<String, Object> itemMap = (Map<String, Object>) event.get("item");
                Map<String, Object> locMap = (Map<String, Object>) event.get("location");

               // ItemStack stack = deserializeItem(itemMap);
                ItemStack stack = deserializeItem(event.get("item"));
                Location loc = deserializeLocation(locMap);

                if (stack != null && loc != null)
                    spawnFakeDroppedItem(stack, loc);

            }
            case "mob_spawn" -> {
                spawnFakeMob(entity, event);
            }
            case "player_quit" -> {
                UUID uuid = UUID.fromString((String) event.get("uuid"));

                recordedEntities.remove(uuid);
                if (entity == null) {
                    return;
                }

                entity.destroy();
                trackedEntityIds.remove(entity.getFakeEntityId());

            }
        }
    }

    private void spawnFakeMob(RecordedEntity entity, Map<String, Object> event) {
        Location loc = new Location(Bukkit.getWorld(asString(event.get("world"))),
                asDouble(event.get("x")),
                asDouble(event.get("y")),
                asDouble(event.get("z")),
                asFloat(event.get("yaw")),
                asFloat(event.get("pitch")));

        entity.spawn(loc);

        trackedEntityIds.add(entity.getFakeEntityId());
        recordedEntities.put(entity.uuid, entity);

        WrapperPlayServerEntityMetadata meta = new WrapperPlayServerEntityMetadata(
                entity.getFakeEntityId(),
                Collections.emptyList()
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, meta);
    }

    private Double asDouble(Object obj) {
        return obj instanceof Number n ? n.doubleValue() : null;
    }

    private Integer asInt(Object obj) {
        return obj instanceof Number n ? n.intValue() : null;
    }

    private Float asFloat(Object obj) {
        return obj instanceof Number n ? n.floatValue() : 0f;
    }

    private String asString(Object obj) {
        return obj instanceof String s ? String.valueOf(s) : null;
    }

    private void primeInitialBrokenBlockStates() {
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

            // Backward compatibility for recordings without replacedBlockData.
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

    private void enrichBlockBreakStageTimeline() {
        if (timeline == null || timeline.isEmpty()) {
            return;
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
            return;
        }

        timeline = new ArrayList<>(timeline);
        timeline.addAll(synthesizedStages);
        timeline.sort(Comparator.comparingInt(event -> {
            Integer tickValue = asInt(event.get("tick"));
            return tickValue != null ? tickValue : Integer.MAX_VALUE;
        }));
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

    private BlockKey blockKeyFromEvent(Map<String, Object> event) {
        String worldName = asString(event.get("world"));
        Integer x = asInt(event.get("x"));
        Integer y = asInt(event.get("y"));
        Integer z = asInt(event.get("z"));

        if (worldName == null || x == null || y == null || z == null) {
            return null;
        }

        return new BlockKey(worldName, x, y, z);
    }

    private void applyReplayBlockChange(Map<String, Object> event, String type, boolean immediateBreakRemoval) {
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
                    if (mutationEpoch != blockBreakMutationEpoch || !isActive()) {
                        return;
                    }
                    viewer.sendBlockChange(blockLoc, Material.AIR.createBlockData());
                },
            3L
        );
    }

    private void restoreSessionBaseline() {
        for (BlockKey key : sessionBaseline.keySet()) {
            World world = Bukkit.getWorld(key.world());
            if (world == null) {
                continue;
            }
            // Send the actual real-world state so the client view matches the server world.
            String realBlockData = world.getBlockAt(key.x(), key.y(), key.z()).getBlockData().getAsString();
            sendBlockStateToViewer(world, key.x(), key.y(), key.z(), realBlockData);
        }
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

    private void showGlobalBlockBreakStage(Map<String, Object> event) {
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

    private void clearVisibleBreakStage(BlockKey key) {
        int animationId = Objects.hash(key.world(), key.x(), key.y(), key.z());
        WrapperPlayServerBlockBreakAnimation clearAnim =
                new WrapperPlayServerBlockBreakAnimation(animationId, new Vector3i(key.x(), key.y(), key.z()), (byte) -1);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, clearAnim);
        visibleBreakStages.remove(key);
    }

    private void clearAllVisibleBreakStages() {
        if (visibleBreakStages.isEmpty()) {
            return;
        }

        Set<BlockKey> staged = new HashSet<>(visibleBreakStages);
        for (BlockKey key : staged) {
            clearVisibleBreakStage(key);
        }
    }

    private void computeAndApplyBlockStateAtIndex(int targetIndexExclusive) {
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

    private void giveReplayControls(Player viewer) {

        ItemStack pauseButton = new ItemStack(Material.RED_DYE);
        ItemMeta pauseMeta = pauseButton.getItemMeta();
        pauseMeta.setDisplayName("§cPause / Play");
        pauseButton.setItemMeta(pauseMeta);

        ItemStack skipForward = new ItemStack(Material.LIME_DYE);
        ItemMeta forwardMeta = skipForward.getItemMeta();
        forwardMeta.setDisplayName("§a+5 seconds");
        skipForward.setItemMeta(forwardMeta);

        ItemStack skipBackward = new ItemStack(Material.YELLOW_DYE);
        ItemMeta backwardMeta = skipBackward.getItemMeta();
        backwardMeta.setDisplayName("§e-5 seconds");
        skipBackward.setItemMeta(backwardMeta);

        ItemStack stopReplay = new ItemStack(Material.BARRIER);
        ItemMeta stopMeta = stopReplay.getItemMeta();
        stopMeta.setDisplayName("§4Exit Replay");
        stopReplay.setItemMeta(stopMeta);

        ItemStack playerMenu = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta menuMeta = playerMenu.getItemMeta();
        menuMeta.setDisplayName("§bPlayers");
        playerMenu.setItemMeta(menuMeta);

        viewer.getInventory().setItem(4, pauseButton);
        viewer.getInventory().setItem(5, skipForward);
        viewer.getInventory().setItem(3, skipBackward);
        viewer.getInventory().setItem(6, playerMenu);
        viewer.getInventory().setItem(8, stopReplay);

        viewer.getInventory().setHeldItemSlot(4);
    }


    private Map<String, Object> getInventorySnapshotForPlayer(UUID uuid) {
        if (timeline.isEmpty()) return null;
        Map<String, Object> firstEvent = timeline.get(0);

        if (!uuid.toString().equals(firstEvent.get("uuid")))
            return null;

        Object inventoryObj = firstEvent.get("inventory");
        if (inventoryObj instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> inventory = (Map<String, Object>) map;
            return inventory;
        }
        return null;
    }


    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        Player player = e.getPlayer();
        if (!player.equals(this.viewer))
            return;

        ItemStack handItem = e.getItem();
        if (handItem == null || !handItem.hasItemMeta())
            return;

        String name = handItem.getItemMeta().getDisplayName();

        // If the player is looking at a recorded player, open their inventory
        // instead of activating the hotbar control. Works at extended range (20 blocks).
        RecordedPlayer targetPlayer = getTargetedRecordedPlayer(player);
        if (targetPlayer != null) {
            targetPlayer.openInventoryForViewer(player);
            e.setCancelled(true);
            return;
        }

        switch (name) {
            case "§cPause / Play" -> togglePause();
            case "§a+5 seconds" -> skipSeconds(5);
            case "§e-5 seconds" -> skipSeconds(-5);
            case "§4Exit Replay" -> stop();
            case "§bPlayers" -> openPlayerMenu();
        }

        e.setCancelled(true);
    }

    private void togglePause() {
        paused = !paused;
    }

    private void skipSeconds(int seconds) {
        if (timeline == null || timeline.isEmpty()) {
            return;
        }

        int currentIndex = Math.max(0, Math.min(tick, timeline.size()));
        int currentRecordedTick = currentIndex > 0 ? getRecordedTickAtIndex(currentIndex - 1) : 0;
        int maxRecordedTick = getRecordedTickAtIndex(timeline.size() - 1);

        int targetRecordedTick = currentRecordedTick + (seconds * 20);
        if (targetRecordedTick < 0) {
            targetRecordedTick = 0;
        }
        if (targetRecordedTick > maxRecordedTick) {
            targetRecordedTick = maxRecordedTick;
        }

        int targetIndex = findTimelineIndexAfterRecordedTick(targetRecordedTick);

        if (targetIndex != currentIndex) {
            blockBreakMutationEpoch++;
        }

        if (targetIndex > currentIndex) {
            applyReplayBlockChangesInRange(currentIndex, targetIndex);
        } else if (targetIndex < currentIndex) {
            rebuildReplayBlockStateUntil(targetIndex);
        }

        syncEntityStatesAtIndex(targetIndex);

        tick = targetIndex;
        sendActionBar();
    }

    private void syncEntityStatesAtIndex(int targetIndex) {
        Map<UUID, Map<String, Object>> firstEventByUUID = new LinkedHashMap<>();
        Map<UUID, Map<String, Object>> lastLocationByUUID = new LinkedHashMap<>();
        Set<UUID> shouldHaveQuitAtTarget = new HashSet<>();
        Set<UUID> shouldBeDeadAtTarget = new HashSet<>();

        int end = Math.min(targetIndex, timeline.size());
        for (int i = 0; i < end; i++) {
            Map<String, Object> event = timeline.get(i);
            Object uuidObj = event.get("uuid");
            if (!(uuidObj instanceof String uuidStr)) continue;
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException ignored) {
                continue;
            }

            String type = asString(event.get("type"));
            firstEventByUUID.putIfAbsent(uuid, event);

            switch (type != null ? type : "") {
                case "player_move", "entity_move" -> lastLocationByUUID.put(uuid, event);
                case "player_quit" -> shouldHaveQuitAtTarget.add(uuid);
                case "entity_death" -> shouldBeDeadAtTarget.add(uuid);
            }
        }

        // Rebuild deadEntities to reflect state at the seek target.
        deadEntities.clear();
        deadEntities.addAll(shouldBeDeadAtTarget);

        Set<UUID> shouldExistAtTarget = new HashSet<>(firstEventByUUID.keySet());
        shouldExistAtTarget.removeAll(shouldHaveQuitAtTarget);
        shouldExistAtTarget.removeAll(shouldBeDeadAtTarget);

        // Destroy entities that no longer belong at this point.
        for (UUID uuid : new HashSet<>(recordedEntities.keySet())) {
            if (!shouldExistAtTarget.contains(uuid)) {
                RecordedEntity entity = recordedEntities.remove(uuid);
                if (entity != null) {
                    entity.destroy();
                    trackedEntityIds.remove(entity.getFakeEntityId());
                }
            }
        }

        // Spawn entities that should exist but haven't been created yet.
        for (UUID uuid : shouldExistAtTarget) {
            if (recordedEntities.containsKey(uuid)) continue;
            if (!lastLocationByUUID.containsKey(uuid)) continue;

            Map<String, Object> firstEvent = firstEventByUUID.get(uuid);
            Map<String, Object> locEvent = lastLocationByUUID.get(uuid);

            Double x = asDouble(locEvent.get("x"));
            Double y = asDouble(locEvent.get("y"));
            Double z = asDouble(locEvent.get("z"));
            String worldName = asString(locEvent.get("world"));
            if (x == null || y == null || z == null || worldName == null) continue;
            World world = Bukkit.getWorld(worldName);
            if (world == null) continue;

            RecordedEntity entity = RecordedEntityFactory.create(firstEvent, viewer);
            if (entity == null) continue;

            entity.spawn(new Location(world, x, y, z,
                    asFloat(locEvent.get("yaw")), asFloat(locEvent.get("pitch"))));
            recordedEntities.put(uuid, entity);
            trackedEntityIds.add(entity.getFakeEntityId());
        }

        // Move all live entities to their last known position at the target index.
        for (Map.Entry<UUID, Map<String, Object>> entry : lastLocationByUUID.entrySet()) {
            RecordedEntity entity = recordedEntities.get(entry.getKey());
            if (entity == null) continue;

            Map<String, Object> event = entry.getValue();
            World world = Bukkit.getWorld(asString(event.get("world")));
            Double x = asDouble(event.get("x"));
            Double y = asDouble(event.get("y"));
            Double z = asDouble(event.get("z"));
            if (world == null || x == null || y == null || z == null) continue;

            entity.moveTo(new Location(world, x, y, z,
                    asFloat(event.get("yaw")), asFloat(event.get("pitch"))));
        }
    }

    private int getRecordedTickAtIndex(int index) {
        if (timeline == null || timeline.isEmpty()) {
            return 0;
        }

        int safeIndex = Math.max(0, Math.min(index, timeline.size() - 1));
        Integer eventTick = asInt(timeline.get(safeIndex).get("tick"));
        return eventTick != null ? eventTick : safeIndex;
    }

    private int findTimelineIndexAfterRecordedTick(int targetRecordedTick) {
        int low = 0;
        int high = timeline.size() - 1;
        int result = timeline.size();

        while (low <= high) {
            int mid = (low + high) >>> 1;
            int midTick = getRecordedTickAtIndex(mid);

            if (midTick > targetRecordedTick) {
                result = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }

        return Math.max(0, Math.min(result, timeline.size()));
    }

    private void applyReplayBlockChangesInRange(int fromIndex, int toIndexExclusive) {
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

    private void rebuildReplayBlockStateUntil(int targetIndexExclusive) {
        clearAllVisibleBreakStages();
        computeAndApplyBlockStateAtIndex(targetIndexExclusive);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent e) {
        Player player = (Player) e.getWhoClicked();
        if (!player.equals(viewer))
            return;

        if(!isActive())
            return;

        e.setCancelled(true);

    }

    @EventHandler
    public void onPlayerMenuClick(InventoryClickEvent e) {
        if (!e.getView().getTitle().equals("§8Recorded Players"))
            return;

        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player player))
            return;

        ItemStack item = e.getCurrentItem();
        if (item == null || !(item.getItemMeta() instanceof SkullMeta meta))
            return;

        OfflinePlayer target = meta.getOwningPlayer();
        if (target == null)
            return;


        RecordedEntity recorded = recordedEntities.get(target.getUniqueId());
        if (recorded == null)
            return;

        replay.getFoliaLib().getScheduler().teleportAsync(player, recorded.getCurrentLocation());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (event.getPlayer().equals(viewer))
            stop();
    }


    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() != viewer)
            return;
        if (!isActive())
            return;
        if (!e.getView().getTitle().contains("'s Inventory"))
            return;

        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        Player player = e.getPlayer();

        if (!isActive())
            return;

        if (!player.equals(viewer))
            return;

        ItemStack item = e.getItemDrop().getItemStack();
        if (item == null || !item.hasItemMeta())
            return;

        String name = item.getItemMeta().getDisplayName();
        if (name.equals("§cPause / Play") || name.equals("§a+5 seconds") || name.equals("§e-5 seconds")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityInteract(PlayerInteractAtEntityEvent e) {
        Player viewerPlayer = e.getPlayer();

        if (!viewer.equals(viewerPlayer))
            return;

        if (!(e.getRightClicked() instanceof Player fake))
            return;

        RecordedEntity recordedEntity = recordedEntities.get(fake.getEntityId());
        if (!(recordedEntity instanceof RecordedPlayer rp))
            return;

        rp.openInventoryForViewer(viewerPlayer);

        e.setCancelled(true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY))
            return;

        if (!event.getPlayer().equals(viewer))
            return;

        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);

        if (trackedEntityIds.contains(wrapper.getEntityId()))
            event.setCancelled(true);

        int entityId = wrapper.getEntityId();
        RecordedEntity recordedEntity = recordedEntities.values()
                .stream()
                .filter(e -> e.getFakeEntityId() == entityId)
                .findFirst()
                .orElse(null);

        if (recordedEntity instanceof RecordedPlayer rp) {
            rp.openInventoryForViewer(viewer);
            event.setCancelled(true);
        }
    }

    /**
     * Return the RecordedPlayer the viewer is aiming at, or null if none.
     * Works up to 20 blocks away — much farther than Minecraft's native interaction range.
     */
    private RecordedPlayer getTargetedRecordedPlayer(Player player) {
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector dir = eye.getDirection().normalize();

        RecordedPlayer closest = null;
        double closestDist = Double.MAX_VALUE;

        for (RecordedEntity re : recordedEntities.values()) {
            if (!(re instanceof RecordedPlayer rp)) continue;

            Location loc = re.getCurrentLocation();
            if (loc == null || !eye.getWorld().equals(loc.getWorld()))
                continue;

            // Aim at entity center (approx 0.9 blocks above feet)
            org.bukkit.util.Vector toEntity = loc.toVector()
                    .add(new org.bukkit.util.Vector(0, 0.9, 0))
                    .subtract(eye.toVector());

            double distance = toEntity.length();
            if (distance > 20.0) continue;

            double dot = toEntity.dot(dir);
            if (dot < 0) continue;

            // Perpendicular distance squared from the look ray
            double perpDistSq = toEntity.crossProduct(dir).lengthSquared();
            if (perpDistSq < 2.25 && distance < closestDist) {
                closest = rp;
                closestDist = distance;
            }
        }
        return closest;
    }

    public RecordedEntity getRecordedEntity(int entityId) {
        for (RecordedEntity e : recordedEntities.values()) {
            if (e.getFakeEntityId() == entityId)
                return e;
        }
        return null;
    }

    private boolean isActive() {
        return ReplayRegistry.contains(this);
    }

    private Location deserializeLocation(Map<String, Object> map) {
        if (map == null)
            return null;
        double x = ((Number) map.get("x")).doubleValue();
        double y = ((Number) map.get("y")).doubleValue();
        double z = ((Number) map.get("z")).doubleValue();
        float yaw = map.get("yaw") instanceof Number n ? n.floatValue() : 0f;
        float pitch = map.get("pitch") instanceof Number n ? n.floatValue() : 0f;
        String world = map.get("world").toString();

        return new Location(Bukkit.getWorld(world), x, y, z, yaw, pitch);
    }

    private void spawnFakeDroppedItem(ItemStack stack, Location loc) {
        int entityId = SpigotReflectionUtil.generateEntityId();
        trackedEntityIds.add(entityId);

        com.github.retrooper.packetevents.protocol.item.ItemStack nmsStack = SpigotConversionUtil.fromBukkitItemStack(stack);

        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
        entityId,
        UUID.randomUUID(),
        EntityTypes.ITEM,
        SpigotConversionUtil.fromBukkitLocation(loc),
        loc.getYaw(),
        0,
        null
        );

        WrapperPlayServerEntityMetadata meta = new WrapperPlayServerEntityMetadata(
                entityId,
                Collections.singletonList(
                        new EntityData(8, EntityDataTypes.ITEMSTACK, nmsStack)
                )
        );

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, spawn);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, meta);
    }

    private void openPlayerMenu() {
        Inventory inv = Bukkit.createInventory(
                null,
                27,
                "§8Recorded Players"
        );

        for (RecordedEntity entity : recordedEntities.values()) {
            if (!(entity instanceof RecordedPlayer rp))
                continue;

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(rp.getUuid()));
            meta.setDisplayName("§e" + rp.getName());
            head.setItemMeta(meta);

            inv.addItem(head);
        }

        viewer.openInventory(inv);
    }

    private String formatTime(int ticks) {
        int seconds = ticks / 20;
        int minutes = seconds / 60;
        seconds %= 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private void sendActionBar() {
        int totalTicks = timeline.size();

        String current = formatTime(tick);
        String total = formatTime(totalTicks);
        int percent = totalTicks > 0 ? (tick * 100 / totalTicks) : 0;

        Component bar;

        if (paused) {
            bar = Component.text("⏸ Replay paused: ", NamedTextColor.YELLOW)
                    .append(Component.text(current + " / " + total, NamedTextColor.GRAY));
        } else {
            bar = Component.text("▶ Replay: ", NamedTextColor.GREEN)
                    .append(Component.text(current + " / " + total, NamedTextColor.GRAY))
                    .append(Component.text(" (" + percent + "%)", NamedTextColor.DARK_GRAY));
        }
        viewer.sendActionBar(bar);
    }
}

