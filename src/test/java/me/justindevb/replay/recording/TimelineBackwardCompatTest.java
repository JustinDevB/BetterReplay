package me.justindevb.replay.recording;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.justindevb.replay.storage.JsonReplayStorageCodec;
import me.justindevb.replay.util.io.SerializedItemData;
import me.justindevb.replay.util.VersionUtil;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests backward compatibility of the timeline format across versions.
 */
class TimelineBackwardCompatTest {

    private final Gson gson = new GsonBuilder()
            .registerTypeHierarchyAdapter(TimelineEvent.class, new TimelineEventAdapter())
            .create();
    private final JsonReplayStorageCodec codec = new JsonReplayStorageCodec();

    private static SerializedItemData slot(byte... bytes) {
        return SerializedItemData.fromBytes(bytes);
    }

    // ── Missing Optional Fields ───────────────────────────────

    @Nested
    class MissingFields {

        @Test
        void playerMove_noPose_defaultsToNull() {
            String json = """
                    {"type":"player_move","tick":0,"uuid":"u","name":"Steve","world":"w",
                     "x":1,"y":2,"z":3,"yaw":0,"pitch":0}
                    """;
            TimelineEvent.PlayerMove event = (TimelineEvent.PlayerMove) gson.fromJson(json, TimelineEvent.class);
            assertNull(event.pose());
        }

        @Test
        void playerMove_noName_defaultsToNull() {
            String json = """
                    {"type":"player_move","tick":0,"uuid":"u","world":"w",
                     "x":0,"y":0,"z":0,"yaw":0,"pitch":0}
                    """;
            TimelineEvent.PlayerMove event = (TimelineEvent.PlayerMove) gson.fromJson(json, TimelineEvent.class);
            assertNull(event.name());
        }

        @Test
        void blockPlace_noReplacedBlockData() {
            String json = """
                    {"type":"block_place","tick":0,"uuid":"u","world":"w",
                     "x":0,"y":0,"z":0,"blockData":"minecraft:stone"}
                    """;
            TimelineEvent.BlockPlace event = (TimelineEvent.BlockPlace) gson.fromJson(json, TimelineEvent.class);
            assertNull(event.replacedBlockData());
        }

        @Test
        void blockBreak_noBlockData() {
            String json = """
                    {"type":"block_break","tick":0,"uuid":"u","world":"w",
                     "x":0,"y":0,"z":0}
                    """;
            TimelineEvent.BlockBreak event = (TimelineEvent.BlockBreak) gson.fromJson(json, TimelineEvent.class);
            assertNull(event.blockData());
        }

        @Test
        void legacyInventoryUpdate_upgradesToEquipmentAndStorage() throws Exception {
            String json = """
                    {"type":"inventory_update","tick":0,"uuid":"u","mainHand":"mh","offHand":"oh"}
                    """;
            List<TimelineEvent> events = codec.decodeTimeline(json.getBytes(StandardCharsets.UTF_8), "1.4.0");
            assertEquals(2, events.size());
            assertInstanceOf(TimelineEvent.EquipmentStateUpdate.class, events.get(0));
            assertInstanceOf(TimelineEvent.InventoryStorageUpdate.class, events.get(1));
            TimelineEvent.EquipmentStateUpdate equipment = (TimelineEvent.EquipmentStateUpdate) events.get(0);
            TimelineEvent.InventoryStorageUpdate storage = (TimelineEvent.InventoryStorageUpdate) events.get(1);
            assertEquals(4, equipment.armor().size());
            assertEquals(36, storage.storage().size());
        }

        @Test
        void legacyInventoryUpdate_allowsNullContentSlots() throws Exception {
            String json = """
                    {"type":"inventory_update","tick":0,"uuid":"u","contents":[null,null,null]}
                    """;

            List<TimelineEvent> events = codec.decodeTimeline(json.getBytes(StandardCharsets.UTF_8), "1.4.0");

            assertEquals(2, events.size());
            TimelineEvent.InventoryStorageUpdate storage = (TimelineEvent.InventoryStorageUpdate) events.get(1);
            assertEquals(36, storage.storage().size());
            assertTrue(storage.storage().stream().allMatch(SerializedItemData::isEmpty));
        }

        @Test
        void attack_noTargetUuid() {
            String json = """
                    {"type":"attack","tick":0,"uuid":"u","entityUuid":"eu","entityType":"ZOMBIE"}
                    """;
            TimelineEvent.Attack event = (TimelineEvent.Attack) gson.fromJson(json, TimelineEvent.class);
            assertNull(event.targetUuid());
        }

        @Test
        void blockBreakStage_noUuid() {
            String json = """
                    {"type":"block_break_stage","tick":0,"world":"w","x":1,"y":2,"z":3,"stage":5}
                    """;
            TimelineEvent.BlockBreakStage event = (TimelineEvent.BlockBreakStage) gson.fromJson(json, TimelineEvent.class);
            assertNull(event.uuid());
            assertEquals(5, event.stage());
        }

        @Test
        void missingCoordinates_defaultToZero() {
            String json = """
                    {"type":"entity_spawn","tick":0,"uuid":"u","etype":"ZOMBIE","world":"w"}
                    """;
            TimelineEvent.EntitySpawn event = (TimelineEvent.EntitySpawn) gson.fromJson(json, TimelineEvent.class);
            assertEquals(0, event.x(), 0.001);
            assertEquals(0, event.y(), 0.001);
            assertEquals(0, event.z(), 0.001);
        }
    }

    // ── Legacy Type Aliases ───────────────────────────────────

    @Nested
    class LegacyAliases {

        @Test
        void mob_spawn_alias() {
            String json = """
                    {"type":"mob_spawn","tick":0,"uuid":"u","etype":"ZOMBIE","world":"w","x":0,"y":0,"z":0}
                    """;
            assertInstanceOf(TimelineEvent.EntitySpawn.class, gson.fromJson(json, TimelineEvent.class));
        }

        @Test
        void block_break_start_alias() {
            String json = """
                    {"type":"block_break_start","tick":0,"uuid":"u","world":"w","x":0,"y":0,"z":0}
                    """;
            assertInstanceOf(TimelineEvent.BlockBreakComplete.class, gson.fromJson(json, TimelineEvent.class));
        }
    }

    // ── Legacy Format: Raw Array ──────────────────────────────

    @Nested
    class LegacyRawArray {

        @Test
        void parsesLegacyRawArray() {
            String json = """
                    [
                      {"type":"player_move","tick":0,"uuid":"u","name":"Steve","world":"w","x":0,"y":0,"z":0,"yaw":0,"pitch":0},
                      {"type":"player_quit","tick":5,"uuid":"u"}
                    ]
                    """;
                        List<TimelineEvent> events = assertDoesNotThrow(() -> codec.decodeTimeline(json.getBytes(StandardCharsets.UTF_8), "1.4.0"));
            assertEquals(2, events.size());
            assertInstanceOf(TimelineEvent.PlayerMove.class, events.get(0));
            assertInstanceOf(TimelineEvent.PlayerQuit.class, events.get(1));
        }
    }

    // ── ItemDrop location ─────────────────────────────────────

    @Nested
    class ItemDropLocation {

        @Test
        void locKey() {
            String json = """
                    {"type":"item_drop","tick":0,"uuid":"u","item":"i",
                     "loc":{"world":"w","x":1,"y":2,"z":3,"yaw":4,"pitch":5}}
                    """;
            TimelineEvent.ItemDrop event = (TimelineEvent.ItemDrop) gson.fromJson(json, TimelineEvent.class);
            assertEquals("w", event.locWorld());
            assertEquals(1.0, event.locX(), 0.001);
        }

        @Test
        void locationKey_legacy() {
            String json = """
                    {"type":"item_drop","tick":0,"uuid":"u","item":"i",
                     "location":{"world":"w2","x":10,"y":20,"z":30,"yaw":0,"pitch":0}}
                    """;
            TimelineEvent.ItemDrop event = (TimelineEvent.ItemDrop) gson.fromJson(json, TimelineEvent.class);
            assertEquals("w2", event.locWorld());
        }

        @Test
        void noLocation_defaultsToZero() {
            String json = """
                    {"type":"item_drop","tick":0,"uuid":"u","item":"i"}
                    """;
            TimelineEvent.ItemDrop event = (TimelineEvent.ItemDrop) gson.fromJson(json, TimelineEvent.class);
            assertNull(event.locWorld());
            assertEquals(0, event.locX(), 0.001);
        }
    }

    // ── Full timeline roundtrip ───────────────────────────────

    @Test
    void fullTimeline_serializeDeserialize_roundtrip() {
        List<TimelineEvent> events = List.of(
                new TimelineEvent.PlayerMove(0, "u1", "Steve", "world", 1, 64, 3, 0, 0, "STANDING"),
                new TimelineEvent.EntityMove(1, "u2", "ZOMBIE", "world", 5, 65, 8, 180, 0),
            new TimelineEvent.EquipmentStateUpdate(2, "u1", 0, slot((byte) 1), slot((byte) 2), List.of(slot((byte) 3), slot((byte) 4), slot((byte) 5), slot((byte) 6))),
            new TimelineEvent.InventoryStorageUpdate(2, "u1", List.of(slot((byte) 7), slot((byte) 8))),
                new TimelineEvent.BlockBreak(3, "u1", "world", 10, 64, 20, "minecraft:stone"),
                new TimelineEvent.BlockBreakComplete(4, "u1", "world", 10, 64, 20),
                new TimelineEvent.BlockBreakStage(5, null, "world", 10, 64, 20, 3),
                new TimelineEvent.BlockPlace(6, "u1", "world", 10, 64, 20, "minecraft:cobblestone", "minecraft:air"),
                new TimelineEvent.ItemDrop(7, "u1", "item", "world", 1, 2, 3, 0, 0),
                new TimelineEvent.Attack(8, "u1", "u2", "u2", "ZOMBIE"),
                new TimelineEvent.Swing(9, "u1", "ARM_SWING"),
                new TimelineEvent.Damaged(10, "u1", "PLAYER", "FALL", 5.5),
                new TimelineEvent.SprintToggle(11, "u1", true),
                new TimelineEvent.SneakToggle(12, "u1", false),
                new TimelineEvent.EntitySpawn(13, "u3", "SKELETON", "world", 50, 65, 80),
                new TimelineEvent.EntityDeath(14, "u3", "SKELETON", "world", 50, 65, 80),
                new TimelineEvent.PlayerQuit(15, "u1")
        );

        String json = VersionUtil.wrapTimeline(gson, events, "1.4.0");
    List<TimelineEvent> parsed = assertDoesNotThrow(() -> codec.decodeTimeline(json.getBytes(StandardCharsets.UTF_8), VersionUtil.MIN_RECORDING_VERSION));

        assertEquals(events.size(), parsed.size());
        for (int i = 0; i < events.size(); i++) {
            assertEquals(events.get(i).getClass(), parsed.get(i).getClass(),
                    "Mismatch at index " + i);
            assertEquals(events.get(i).tick(), parsed.get(i).tick());
        }
    }
}
