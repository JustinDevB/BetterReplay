package me.justindevb.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import me.justindevb.replay.entity.RecordedEntity;
import me.justindevb.replay.entity.RecordedEntityFactory;
import me.justindevb.replay.entity.RecordedPlayer;
import me.justindevb.replay.api.events.ReplayStartEvent;
import me.justindevb.replay.api.events.ReplayStopEvent;
import me.justindevb.replay.playback.PlaybackEngine;
import me.justindevb.replay.playback.ReplayBlockManager;
import me.justindevb.replay.playback.ReplayInventoryUI;
import me.justindevb.replay.recording.TimelineEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;

/**
 * Coordinator for a single replay viewing session.
 * Delegates block state management to {@link ReplayBlockManager},
 * event dispatch to {@link PlaybackEngine}, and UI/inventory to {@link ReplayInventoryUI}.
 */
public class ReplaySession implements Listener, PacketListener {

    private final Player viewer;
    private final Replay replay;

    private WrappedTask replayTask = null;
    private List<TimelineEvent> timeline;
    private final Set<Integer> trackedEntityIds = new HashSet<>();
    private final Set<UUID> deadEntities = new HashSet<>();
    private final Map<UUID, RecordedEntity> recordedEntities = new HashMap<>();
    private int tick = 0;
    private boolean paused = false;

    // Delegates
    private final ReplayBlockManager blockManager;
    private final PlaybackEngine playbackEngine;
    private final ReplayInventoryUI inventoryUI;

    public ReplaySession(List<TimelineEvent> timeline, Player viewer, Replay replay) {
        this.timeline = timeline;
        this.viewer = viewer;
        this.replay = replay;

        this.blockManager = new ReplayBlockManager(viewer, replay);
        this.playbackEngine = new PlaybackEngine(viewer, replay, trackedEntityIds, deadEntities, recordedEntities, blockManager);
        this.inventoryUI = new ReplayInventoryUI(viewer, () -> recordedEntities, new ReplayInventoryUI.SessionControl() {
            @Override public void togglePause() { paused = !paused; }
            @Override public void skipSeconds(int seconds) { ReplaySession.this.skipSeconds(seconds); }
            @Override public void stop() { ReplaySession.this.stop(); }
            @Override public boolean isActive() { return ReplaySession.this.isActive(); }
        });

        Bukkit.getPluginManager().registerEvents(this, replay);
        Bukkit.getPluginManager().registerEvents(inventoryUI, replay);
    }

    public void start() {
        if (timeline == null || timeline.isEmpty()) {
            viewer.sendMessage("Replay is empty!");
            return;
        }

        ReplayRegistry.add(this);
        timeline = blockManager.enrichBlockBreakStageTimeline(timeline);
        inventoryUI.copyInventory();

        TimelineEvent firstLocationEvent = timeline.stream()
                .filter(e -> e instanceof TimelineEvent.PlayerMove || e instanceof TimelineEvent.EntityMove
                        || e instanceof TimelineEvent.EntitySpawn)
                .findFirst()
                .orElse(null);

        if (firstLocationEvent != null) {
            Location teleportLoc = switch (firstLocationEvent) {
                case TimelineEvent.PlayerMove e -> new Location(Bukkit.getWorld(e.world()), e.x(), e.y(), e.z(), e.yaw(), e.pitch());
                case TimelineEvent.EntityMove e -> new Location(Bukkit.getWorld(e.world()), e.x(), e.y(), e.z(), e.yaw(), e.pitch());
                case TimelineEvent.EntitySpawn e -> new Location(Bukkit.getWorld(e.world()), e.x(), e.y(), e.z(), 0f, 0f);
                default -> null;
            };
            if (teleportLoc != null && teleportLoc.getWorld() != null) {
                replay.getFoliaLib().getScheduler().teleportAsync(viewer, teleportLoc);
            }
        }

        inventoryUI.giveReplayControls();
        blockManager.primeInitialBrokenBlockStates(timeline);

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

            TimelineEvent firstEvent = timeline.get(tick);
            int recordedTick = firstEvent.tick();

            while (tick < timeline.size()) {
                TimelineEvent event = timeline.get(tick);
                int eventTick = event.tick();
                if (eventTick != recordedTick) break;

                if (event instanceof TimelineEvent.BlockBreakStage bbs) {
                    blockManager.showGlobalBlockBreakStage(bbs);
                    tick++;
                    continue;
                }

                String uuidStr = event.uuid();
                if (uuidStr == null) {
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

                if (event instanceof TimelineEvent.PlayerQuit) {
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

                if (deadEntities.contains(uuid)
                        && (event instanceof TimelineEvent.PlayerMove || event instanceof TimelineEvent.EntityMove)) {
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
                    Location initialLoc = locationFromEvent(event);
                    if (initialLoc == null) {
                        tick++;
                        continue;
                    }

                    recorded = RecordedEntityFactory.create(event, viewer);
                    if (recorded == null) {
                        tick++;
                        continue;
                    }

                    recorded.spawn(initialLoc);
                    recordedEntities.put(uuid, recorded);

                    if (recorded instanceof RecordedPlayer rp) {
                        TimelineEvent.InventoryUpdate inv = getInventorySnapshotForPlayer(uuid);
                        if (inv != null) rp.updateInventory(inv);
                    }
                }

                playbackEngine.handleEvent(recorded, event);
                tick++;
            }
            sendActionBar();
        }, 1L, 1L);
    }

    public void stop() {
        viewer.sendActionBar(Component.empty());

        Bukkit.getPluginManager().callEvent(new ReplayStopEvent(viewer, this));
        recordedEntities.values().forEach(RecordedEntity::destroy);
        recordedEntities.clear();

        clearFakeItems();
        blockManager.incrementEpoch();
        blockManager.clearAllVisibleBreakStages();
        blockManager.restoreSessionBaseline();
        inventoryUI.restoreInventory();
        if (replayTask != null) {
            replay.getFoliaLib().getScheduler().cancelTask(replayTask);
            replayTask = null;
        }

        viewer.sendMessage("Replay finished");
        ReplayRegistry.remove(this);
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(inventoryUI);
    }

    // -- Skip / Seek --

    private void skipSeconds(int seconds) {
        if (timeline == null || timeline.isEmpty()) return;

        int currentIndex = Math.max(0, Math.min(tick, timeline.size()));
        int currentRecordedTick = currentIndex > 0 ? getRecordedTickAtIndex(currentIndex - 1) : 0;
        int maxRecordedTick = getRecordedTickAtIndex(timeline.size() - 1);

        int targetRecordedTick = currentRecordedTick + (seconds * 20);
        if (targetRecordedTick < 0) targetRecordedTick = 0;
        if (targetRecordedTick > maxRecordedTick) targetRecordedTick = maxRecordedTick;

        int targetIndex = findTimelineIndexAfterRecordedTick(targetRecordedTick);

        if (targetIndex != currentIndex) {
            blockManager.incrementEpoch();
        }

        if (targetIndex > currentIndex) {
            blockManager.applyReplayBlockChangesInRange(currentIndex, targetIndex, timeline);
        } else if (targetIndex < currentIndex) {
            blockManager.rebuildReplayBlockStateUntil(targetIndex, timeline);
        }

        syncEntityStatesAtIndex(targetIndex);
        tick = targetIndex;
        sendActionBar();
    }

    private void syncEntityStatesAtIndex(int targetIndex) {
        Map<UUID, TimelineEvent> firstEventByUUID = new LinkedHashMap<>();
        Map<UUID, TimelineEvent> lastLocationByUUID = new LinkedHashMap<>();
        Map<UUID, TimelineEvent.InventoryUpdate> lastInventoryByUUID = new LinkedHashMap<>();
        Set<UUID> shouldHaveQuitAtTarget = new HashSet<>();
        Set<UUID> shouldBeDeadAtTarget = new HashSet<>();

        int end = Math.min(targetIndex, timeline.size());
        for (int i = 0; i < end; i++) {
            TimelineEvent event = timeline.get(i);
            String uuidStr = event.uuid();
            if (uuidStr == null) continue;
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException ignored) {
                continue;
            }

            firstEventByUUID.putIfAbsent(uuid, event);

            switch (event) {
                case TimelineEvent.PlayerMove ignored2 -> lastLocationByUUID.put(uuid, event);
                case TimelineEvent.EntityMove ignored2 -> lastLocationByUUID.put(uuid, event);
                case TimelineEvent.InventoryUpdate inv -> lastInventoryByUUID.put(uuid, inv);
                case TimelineEvent.PlayerQuit ignored2 -> shouldHaveQuitAtTarget.add(uuid);
                case TimelineEvent.EntityDeath ignored2 -> shouldBeDeadAtTarget.add(uuid);
                default -> {}
            }
        }

        deadEntities.clear();
        deadEntities.addAll(shouldBeDeadAtTarget);

        Set<UUID> shouldExistAtTarget = new HashSet<>(firstEventByUUID.keySet());
        shouldExistAtTarget.removeAll(shouldHaveQuitAtTarget);
        shouldExistAtTarget.removeAll(shouldBeDeadAtTarget);

        for (UUID uuid : new HashSet<>(recordedEntities.keySet())) {
            if (!shouldExistAtTarget.contains(uuid)) {
                RecordedEntity entity = recordedEntities.remove(uuid);
                if (entity != null) {
                    entity.destroy();
                    trackedEntityIds.remove(entity.getFakeEntityId());
                }
            }
        }

        for (UUID uuid : shouldExistAtTarget) {
            if (recordedEntities.containsKey(uuid)) continue;
            if (!lastLocationByUUID.containsKey(uuid)) continue;

            TimelineEvent firstEvent = firstEventByUUID.get(uuid);
            TimelineEvent locEvent = lastLocationByUUID.get(uuid);

            Location loc = locationFromEvent(locEvent);
            if (loc == null) continue;

            RecordedEntity entity = RecordedEntityFactory.create(firstEvent, viewer);
            if (entity == null) continue;

            entity.spawn(loc);
            recordedEntities.put(uuid, entity);
            trackedEntityIds.add(entity.getFakeEntityId());
        }

        for (Map.Entry<UUID, TimelineEvent> entry : lastLocationByUUID.entrySet()) {
            RecordedEntity entity = recordedEntities.get(entry.getKey());
            if (entity == null) continue;
            Location loc = locationFromEvent(entry.getValue());
            if (loc == null) continue;
            entity.moveTo(loc);
        }

        for (Map.Entry<UUID, TimelineEvent.InventoryUpdate> entry : lastInventoryByUUID.entrySet()) {
            RecordedEntity entity = recordedEntities.get(entry.getKey());
            if (entity instanceof RecordedPlayer rp) {
                rp.updateInventory(entry.getValue());
            }
        }
    }

    // -- Helpers --

    private void clearFakeItems() {
        for (int id : trackedEntityIds) {
            WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(id);
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, destroy);
        }
        trackedEntityIds.clear();
    }

    private TimelineEvent.InventoryUpdate getInventorySnapshotForPlayer(UUID uuid) {
        String uuidStr = uuid.toString();
        for (TimelineEvent event : timeline) {
            if (event instanceof TimelineEvent.InventoryUpdate inv
                    && uuidStr.equals(inv.uuid())) {
                return inv;
            }
        }
        return null;
    }

    private int getRecordedTickAtIndex(int index) {
        if (timeline == null || timeline.isEmpty()) return 0;
        int safeIndex = Math.max(0, Math.min(index, timeline.size() - 1));
        return timeline.get(safeIndex).tick();
    }

    private Location locationFromEvent(TimelineEvent event) {
        return switch (event) {
            case TimelineEvent.PlayerMove e -> {
                World w = Bukkit.getWorld(e.world());
                yield w != null ? new Location(w, e.x(), e.y(), e.z(), e.yaw(), e.pitch()) : null;
            }
            case TimelineEvent.EntityMove e -> {
                World w = Bukkit.getWorld(e.world());
                yield w != null ? new Location(w, e.x(), e.y(), e.z(), e.yaw(), e.pitch()) : null;
            }
            case TimelineEvent.EntitySpawn e -> {
                World w = Bukkit.getWorld(e.world());
                yield w != null ? new Location(w, e.x(), e.y(), e.z(), 0f, 0f) : null;
            }
            default -> null;
        };
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

    private boolean isActive() {
        return ReplayRegistry.contains(this);
    }

    public RecordedEntity getRecordedEntity(int entityId) {
        for (RecordedEntity e : recordedEntities.values()) {
            if (e.getFakeEntityId() == entityId)
                return e;
        }
        return null;
    }

    private String formatTime(int ticks) {
        int seconds = ticks / 20;
        int minutes = seconds / 60;
        seconds %= 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private void sendActionBar() {
        int currentRecordedTick = tick > 0 ? getRecordedTickAtIndex(tick - 1) : 0;
        int totalRecordedTicks = getRecordedTickAtIndex(timeline.size() - 1);
        String current = formatTime(currentRecordedTick);
        String total = formatTime(totalRecordedTicks);
        int percent = totalRecordedTicks > 0 ? (currentRecordedTick * 100 / totalRecordedTicks) : 0;

        Component bar;
        if (paused) {
            bar = Component.text("\u23F8 Replay paused: ", NamedTextColor.YELLOW)
                    .append(Component.text(current + " / " + total, NamedTextColor.GRAY));
        } else {
            bar = Component.text("\u25B6 Replay: ", NamedTextColor.GREEN)
                    .append(Component.text(current + " / " + total, NamedTextColor.GRAY))
                    .append(Component.text(" (" + percent + "%)", NamedTextColor.DARK_GRAY));
        }
        viewer.sendActionBar(bar);
    }

    // -- Remaining event handlers that stay on the session --

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (event.getPlayer().equals(viewer))
            stop();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityInteract(PlayerInteractAtEntityEvent e) {
        Player viewerPlayer = e.getPlayer();
        if (!viewer.equals(viewerPlayer))
            return;
        if (!(e.getRightClicked() instanceof Player fake))
            return;
        RecordedEntity recordedEntity = recordedEntities.get(fake.getUniqueId());
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

    public Player getViewer() {
        return viewer;
    }
}
