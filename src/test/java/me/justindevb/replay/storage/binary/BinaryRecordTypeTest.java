package me.justindevb.replay.storage.binary;

import me.justindevb.replay.recording.TimelineEvent;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BinaryRecordTypeTest {

    @Test
    void definesStableTagsForEveryTimelineEventSubtype() {
        assertEquals(0x01, BinaryRecordType.PLAYER_MOVE.tag());
        assertEquals(0x02, BinaryRecordType.ENTITY_MOVE.tag());
        assertEquals(0x03, BinaryRecordType.INVENTORY_UPDATE.tag());
        assertEquals(0x04, BinaryRecordType.HELD_ITEM_CHANGE.tag());
        assertEquals(0x05, BinaryRecordType.BLOCK_BREAK.tag());
        assertEquals(0x06, BinaryRecordType.BLOCK_BREAK_COMPLETE.tag());
        assertEquals(0x07, BinaryRecordType.BLOCK_BREAK_STAGE.tag());
        assertEquals(0x08, BinaryRecordType.BLOCK_PLACE.tag());
        assertEquals(0x09, BinaryRecordType.ITEM_DROP.tag());
        assertEquals(0x0A, BinaryRecordType.ATTACK.tag());
        assertEquals(0x0B, BinaryRecordType.SWING.tag());
        assertEquals(0x0C, BinaryRecordType.DAMAGED.tag());
        assertEquals(0x0D, BinaryRecordType.SPRINT_TOGGLE.tag());
        assertEquals(0x0E, BinaryRecordType.SNEAK_TOGGLE.tag());
        assertEquals(0x0F, BinaryRecordType.ENTITY_SPAWN.tag());
        assertEquals(0x10, BinaryRecordType.ENTITY_DEATH.tag());
        assertEquals(0x11, BinaryRecordType.PLAYER_QUIT.tag());
    }

    @Test
    void mapsEveryPermittedTimelineEventSubtype() {
        Set<Class<?>> expected = Set.of(TimelineEvent.class.getPermittedSubclasses());
        Set<Class<?>> actual = BinaryRecordType.mappedEventTypes().stream()
                .map(type -> (Class<?>) type)
                .collect(Collectors.toSet());

        assertEquals(expected, actual);
    }

    @Test
    void usesUniqueTags() {
        long uniqueTagCount = Arrays.stream(BinaryRecordType.values())
                .map(BinaryRecordType::tag)
                .distinct()
                .count();

        assertEquals(BinaryRecordType.values().length, uniqueTagCount);
    }

    @Test
    void resolvesKnownTagsAndRejectsUnknownOnes() {
        assertEquals(BinaryRecordType.PLAYER_MOVE, BinaryRecordType.fromTag(0x01).orElseThrow());
        assertEquals(BinaryRecordType.DEFINE_STRING, BinaryRecordType.fromTag(0x00).orElseThrow());
        assertTrue(BinaryRecordType.fromTag(0x7F).isEmpty());
    }

    @Test
    void resolvesRecordTypeFromConcreteEventInstance() {
        List<TimelineEvent> samples = List.of(
                new TimelineEvent.PlayerMove(0, "u1", "Steve", "world", 1.0, 2.0, 3.0, 4.0f, 5.0f, "STANDING"),
                new TimelineEvent.EntityMove(1, "u2", "ZOMBIE", "world", 1.0, 2.0, 3.0, 4.0f, 5.0f),
                new TimelineEvent.InventoryUpdate(2, "u3", "mh", "oh", List.of("a"), List.of("c")),
                new TimelineEvent.HeldItemChange(3, "u4", "mh", "oh"),
                new TimelineEvent.BlockBreak(4, "u5", "world", 1, 2, 3, "minecraft:stone"),
                new TimelineEvent.BlockBreakComplete(5, "u6", "world", 1, 2, 3),
                new TimelineEvent.BlockBreakStage(6, null, "world", 1, 2, 3, 4),
                new TimelineEvent.BlockPlace(7, "u7", "world", 1, 2, 3, "minecraft:stone", "minecraft:air"),
                new TimelineEvent.ItemDrop(8, "u8", "item", "world", 1.0, 2.0, 3.0, 4.0f, 5.0f),
                new TimelineEvent.Attack(9, "u9", "target", "entity", "ZOMBIE"),
                new TimelineEvent.Swing(10, "u10", "MAIN_HAND"),
                new TimelineEvent.Damaged(11, "u11", "PLAYER", "FALL", 2.5),
                new TimelineEvent.SprintToggle(12, "u12", true),
                new TimelineEvent.SneakToggle(13, "u13", false),
                new TimelineEvent.EntitySpawn(14, "u14", "ZOMBIE", "world", 1.0, 2.0, 3.0),
                new TimelineEvent.EntityDeath(15, "u15", "ZOMBIE", "world", 1.0, 2.0, 3.0),
                new TimelineEvent.PlayerQuit(16, "u16")
        );

        assertIterableEquals(
                List.of(BinaryRecordType.eventValues()),
                samples.stream().map(BinaryRecordType::forEvent).toList());
    }
}