package me.justindevb.replay;

import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.util.io.SerializedItemData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReplaySessionTest {

    @Test
    void collectEntityCreationEventsForSeek_ignoresInventoryEventsBeforePlayerMove() {
        UUID uuid = UUID.randomUUID();
        String uuidString = uuid.toString();
        TimelineEvent.PlayerMove playerMove = new TimelineEvent.PlayerMove(
                0,
                uuidString,
                "Steve",
                "world",
                1.0,
                64.0,
                1.0,
                0.0f,
                0.0f,
                "STANDING");

        List<TimelineEvent> timeline = List.of(
                new TimelineEvent.EquipmentStateUpdate(
                        0,
                        uuidString,
                        0,
                        SerializedItemData.empty(),
                        SerializedItemData.empty(),
                        List.of(SerializedItemData.empty(), SerializedItemData.empty(), SerializedItemData.empty(), SerializedItemData.empty())),
                new TimelineEvent.InventoryStorageUpdate(
                        0,
                        uuidString,
                        List.of(SerializedItemData.empty())),
                playerMove);

        Map<UUID, TimelineEvent> creationEvents = ReplaySession.collectEntityCreationEventsForSeek(timeline, timeline.size());

        assertSame(playerMove, creationEvents.get(uuid));
    }

    @Test
    void collectEntityCreationEventsForSeek_ignoresInvalidUuids() {
        List<TimelineEvent> timeline = List.of(
                new TimelineEvent.PlayerMove(0, "not-a-uuid", "Steve", "world", 0, 64, 0, 0, 0, "STANDING"));

        Map<UUID, TimelineEvent> creationEvents = ReplaySession.collectEntityCreationEventsForSeek(timeline, timeline.size());

        assertTrue(creationEvents.isEmpty());
    }
}
