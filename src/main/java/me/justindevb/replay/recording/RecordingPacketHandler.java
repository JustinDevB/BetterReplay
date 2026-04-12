package me.justindevb.replay.recording;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles PacketEvents packet interception during a recording session.
 * Captures block break animation stages for the timeline.
 */
public class RecordingPacketHandler implements PacketListener {

    private final EntityTracker tracker;
    private final TimelineBuilder builder;
    private final RecordingEventHandler.TickProvider tickProvider;
    private final Map<String, Integer> breakStageDedup = new HashMap<>();

    public RecordingPacketHandler(EntityTracker tracker, TimelineBuilder builder, RecordingEventHandler.TickProvider tickProvider) {
        this.tracker = tracker;
        this.builder = builder;
        this.tickProvider = tickProvider;
    }

    @Override
    public void onPacketSend(PacketSendEvent e) {
        if (e.getPacketType() == PacketType.Play.Server.BLOCK_BREAK_ANIMATION) {
            WrapperPlayServerBlockBreakAnimation packet = new WrapperPlayServerBlockBreakAnimation(e);
            Player p = e.getPlayer();

            String world = p.getWorld().getName();

            int stage = packet.getDestroyStage();
            int x = packet.getBlockPosition().getX();
            int y = packet.getBlockPosition().getY();
            int z = packet.getBlockPosition().getZ();

            int tick = tickProvider.getTick();

            String dedupKey = world + ":" + x + ":" + y + ":" + z + ":" + stage;
            Integer lastTick = breakStageDedup.get(dedupKey);
            if (lastTick != null && lastTick == tick) {
                return;
            }
            breakStageDedup.put(dedupKey, tick);
            if (breakStageDedup.size() > 4000) {
                breakStageDedup.entrySet().removeIf(entry -> entry.getValue() < tick - 40);
            }

            String breakerUuid = null;
            Entity entity = SpigotConversionUtil.getEntityById(p.getWorld(), packet.getEntityId());
            if (entity instanceof Player breaker && tracker.isTrackedPlayer(breaker.getUniqueId())) {
                breakerUuid = breaker.getUniqueId().toString();
            }

            if (breakerUuid == null && !tracker.isTrackedPlayer(p.getUniqueId())) {
                return;
            }

            Map<String, Object> event = new HashMap<>();
            event.put("tick", tick);
            event.put("type", "block_break_stage");
            if (breakerUuid != null) {
                event.put("uuid", breakerUuid);
            }
            event.put("world", world);
            event.put("x", x);
            event.put("y", y);
            event.put("z", z);
            event.put("stage", stage);
            builder.addEvent(event);
        }
    }
}
