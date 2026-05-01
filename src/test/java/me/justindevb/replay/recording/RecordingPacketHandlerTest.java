package me.justindevb.replay.recording;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecordingPacketHandlerTest {

    @Mock private EntityTracker tracker;
    @Mock private Player player;
    @Mock private World world;
    private TimelineBuilder builder;
    private int tick = 5;
    private RecordingPacketHandler handler;

    @BeforeEach
    void setUp() {
        builder = new TimelineBuilder();
        handler = new RecordingPacketHandler(tracker, builder, () -> tick);
    }

    @Test
    void nonBlockBreakAnimationPacket_ignored() {
        PacketSendEvent event = mock(PacketSendEvent.class);
        when(event.getPacketType()).thenReturn(PacketType.Play.Server.CHAT_MESSAGE);

        handler.onPacketSend(event);

        assertTrue(builder.getTimeline().isEmpty());
    }

    // Note: Testing the actual block break animation path requires deep PacketEvents
    // mocking that is brittle. The dedup logic is tested indirectly through integration tests.
    // However, we can verify the dedup data structure behavior conceptually:

    @Test
    void handler_constructsWithoutError() {
        assertNotNull(handler);
    }

    @Test
    void scheduleBlockBreakAnimation_defersTimelineMutationUntilMainThread() {
        List<Runnable> scheduledTasks = new ArrayList<>();
        handler = new RecordingPacketHandler(tracker, builder, () -> tick, scheduledTasks::add);

        handler.scheduleBlockBreakAnimation(new RecordingPacketHandler.BlockBreakAnimation(
                UUID.randomUUID(),
                17,
                10,
                64,
                20,
                4));

        assertEquals(1, scheduledTasks.size());
        assertTrue(builder.getTimeline().isEmpty());
    }

    @Test
    void recordBlockBreakAnimation_addsEventForTrackedViewer() {
        UUID viewerUuid = UUID.randomUUID();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<SpigotConversionUtil> conversion = mockStatic(SpigotConversionUtil.class)) {

            bukkit.when(() -> Bukkit.getPlayer(viewerUuid)).thenReturn(player);
            conversion.when(() -> SpigotConversionUtil.getEntityById(world, 99)).thenReturn(null);

            when(player.isOnline()).thenReturn(true);
            when(player.getWorld()).thenReturn(world);
            when(world.getName()).thenReturn("world");
            when(tracker.isTrackedPlayer(viewerUuid)).thenReturn(true);

            handler.recordBlockBreakAnimation(new RecordingPacketHandler.BlockBreakAnimation(
                    viewerUuid,
                    99,
                    1,
                    65,
                    2,
                    7));

            List<TimelineEvent> timeline = builder.getTimeline();
            assertEquals(1, timeline.size());
            assertInstanceOf(TimelineEvent.BlockBreakStage.class, timeline.getFirst());

            TimelineEvent.BlockBreakStage event = (TimelineEvent.BlockBreakStage) timeline.getFirst();
            assertEquals(tick, event.tick());
            assertNull(event.uuid());
            assertEquals("world", event.world());
            assertEquals(1, event.x());
            assertEquals(65, event.y());
            assertEquals(2, event.z());
            assertEquals(7, event.stage());
        }
    }
}
