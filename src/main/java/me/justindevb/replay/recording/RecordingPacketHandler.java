package me.justindevb.replay.recording;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles PacketEvents packet interception during a recording session.
 * Captures block break animation stages for the timeline.
 */
public class RecordingPacketHandler implements PacketListener {

    @FunctionalInterface
    public interface MainThreadScheduler {
        void execute(Runnable task);
    }

    record BlockBreakAnimation(UUID viewerUuid, int entityId, int x, int y, int z, int stage) {
    }

    private final EntityTracker tracker;
    private final TimelineBuilder builder;
    private final RecordingEventHandler.TickProvider tickProvider;
    private final MainThreadScheduler mainThreadScheduler;
    private final Map<String, Integer> breakStageDedup = new HashMap<>();

    public RecordingPacketHandler(EntityTracker tracker, TimelineBuilder builder, RecordingEventHandler.TickProvider tickProvider) {
        this(tracker, builder, tickProvider, Runnable::run);
    }

    public RecordingPacketHandler(EntityTracker tracker,
                                  TimelineBuilder builder,
                                  RecordingEventHandler.TickProvider tickProvider,
                                  MainThreadScheduler mainThreadScheduler) {
        this.tracker = tracker;
        this.builder = builder;
        this.tickProvider = tickProvider;
        this.mainThreadScheduler = mainThreadScheduler;
    }

    @Override
    public void onPacketSend(PacketSendEvent e) {
        if (e.getPacketType() != PacketType.Play.Server.BLOCK_BREAK_ANIMATION) {
            return;
        }

        Player viewer = e.getPlayer();
        if (viewer == null) {
            return;
        }

        WrapperPlayServerBlockBreakAnimation packet = new WrapperPlayServerBlockBreakAnimation(e);
        scheduleBlockBreakAnimation(new BlockBreakAnimation(
                viewer.getUniqueId(),
                packet.getEntityId(),
                packet.getBlockPosition().getX(),
                packet.getBlockPosition().getY(),
                packet.getBlockPosition().getZ(),
                packet.getDestroyStage()
        ));
    }

    void scheduleBlockBreakAnimation(BlockBreakAnimation animation) {
        mainThreadScheduler.execute(() -> recordBlockBreakAnimation(animation));
    }

    void recordBlockBreakAnimation(BlockBreakAnimation animation) {
        Player viewer = Bukkit.getPlayer(animation.viewerUuid());
        if (viewer == null || !viewer.isOnline()) {
            return;
        }

        String world = viewer.getWorld().getName();
        int tick = tickProvider.getTick();

        String dedupKey = world + ":" + animation.x() + ":" + animation.y() + ":" + animation.z() + ":" + animation.stage();
        Integer lastTick = breakStageDedup.get(dedupKey);
        if (lastTick != null && lastTick == tick) {
            return;
        }
        breakStageDedup.put(dedupKey, tick);
        if (breakStageDedup.size() > 4000) {
            breakStageDedup.entrySet().removeIf(entry -> entry.getValue() < tick - 40);
        }

        String breakerUuid = null;
        Entity entity = SpigotConversionUtil.getEntityById(viewer.getWorld(), animation.entityId());
        if (entity instanceof Player breaker && tracker.isTrackedPlayer(breaker.getUniqueId())) {
            breakerUuid = breaker.getUniqueId().toString();
        }

        if (breakerUuid == null && !tracker.isTrackedPlayer(animation.viewerUuid())) {
            return;
        }

        builder.addEvent(new TimelineEvent.BlockBreakStage(
                tick, breakerUuid, world, animation.x(), animation.y(), animation.z(), animation.stage()
        ));
    }
}
