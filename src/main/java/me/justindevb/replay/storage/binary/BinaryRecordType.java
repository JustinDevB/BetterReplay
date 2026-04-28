package me.justindevb.replay.storage.binary;

import me.justindevb.replay.recording.TimelineEvent;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stable numeric record tags for the BetterReplay binary replay format.
 */
public enum BinaryRecordType {
    DEFINE_STRING(0x00, null),
    PLAYER_MOVE(0x01, TimelineEvent.PlayerMove.class),
    ENTITY_MOVE(0x02, TimelineEvent.EntityMove.class),
    INVENTORY_UPDATE(0x03, TimelineEvent.InventoryUpdate.class),
    HELD_ITEM_CHANGE(0x04, TimelineEvent.HeldItemChange.class),
    BLOCK_BREAK(0x05, TimelineEvent.BlockBreak.class),
    BLOCK_BREAK_COMPLETE(0x06, TimelineEvent.BlockBreakComplete.class),
    BLOCK_BREAK_STAGE(0x07, TimelineEvent.BlockBreakStage.class),
    BLOCK_PLACE(0x08, TimelineEvent.BlockPlace.class),
    ITEM_DROP(0x09, TimelineEvent.ItemDrop.class),
    ATTACK(0x0A, TimelineEvent.Attack.class),
    SWING(0x0B, TimelineEvent.Swing.class),
    DAMAGED(0x0C, TimelineEvent.Damaged.class),
    SPRINT_TOGGLE(0x0D, TimelineEvent.SprintToggle.class),
    SNEAK_TOGGLE(0x0E, TimelineEvent.SneakToggle.class),
    ENTITY_SPAWN(0x0F, TimelineEvent.EntitySpawn.class),
    ENTITY_DEATH(0x10, TimelineEvent.EntityDeath.class),
    PLAYER_QUIT(0x11, TimelineEvent.PlayerQuit.class);

    private static final Map<Integer, BinaryRecordType> BY_TAG;
    private static final Map<Class<? extends TimelineEvent>, BinaryRecordType> BY_EVENT_TYPE;

    static {
        Map<Integer, BinaryRecordType> byTag = new LinkedHashMap<>();
        Map<Class<? extends TimelineEvent>, BinaryRecordType> byEventType = new LinkedHashMap<>();
        for (BinaryRecordType type : values()) {
            BinaryRecordType duplicateTag = byTag.put(type.tag, type);
            if (duplicateTag != null) {
                throw new ExceptionInInitializerError("Duplicate binary record tag: " + type.tag);
            }
            if (type.eventType != null) {
                BinaryRecordType duplicateEventType = byEventType.put(type.eventType, type);
                if (duplicateEventType != null) {
                    throw new ExceptionInInitializerError("Duplicate timeline event mapping: " + type.eventType.getName());
                }
            }
        }

        Set<Class<?>> expectedEventTypes = Set.of(TimelineEvent.class.getPermittedSubclasses());
        Set<Class<?>> mappedEventTypes = Set.copyOf(byEventType.keySet());
        if (!mappedEventTypes.equals(expectedEventTypes)) {
            Set<Class<?>> missing = expectedEventTypes.stream()
                    .filter(type -> !mappedEventTypes.contains(type))
                    .collect(Collectors.toSet());
            Set<Class<?>> extra = mappedEventTypes.stream()
                    .filter(type -> !expectedEventTypes.contains(type))
                    .collect(Collectors.toSet());
            throw new ExceptionInInitializerError(
                    "Binary record tags are out of sync with TimelineEvent. Missing=" + missing + ", extra=" + extra);
        }

        BY_TAG = Map.copyOf(byTag);
        BY_EVENT_TYPE = Map.copyOf(byEventType);
    }

    private final int tag;
    private final Class<? extends TimelineEvent> eventType;

    BinaryRecordType(int tag, Class<? extends TimelineEvent> eventType) {
        this.tag = tag;
        this.eventType = eventType;
    }

    public int tag() {
        return tag;
    }

    public Optional<Class<? extends TimelineEvent>> eventType() {
        return Optional.ofNullable(eventType);
    }

    public boolean isTimelineEventRecord() {
        return eventType != null;
    }

    public static Optional<BinaryRecordType> fromTag(int tag) {
        return Optional.ofNullable(BY_TAG.get(tag));
    }

    public static BinaryRecordType forEvent(TimelineEvent event) {
        BinaryRecordType type = BY_EVENT_TYPE.get(event.getClass());
        if (type == null) {
            throw new IllegalArgumentException("Unsupported timeline event type: " + event.getClass().getName());
        }
        return type;
    }

    public static Set<Class<? extends TimelineEvent>> mappedEventTypes() {
        return BY_EVENT_TYPE.keySet();
    }

    public static BinaryRecordType[] eventValues() {
        return Arrays.stream(values())
                .filter(BinaryRecordType::isTimelineEventRecord)
                .toArray(BinaryRecordType[]::new);
    }
}