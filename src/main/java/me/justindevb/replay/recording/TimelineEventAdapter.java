package me.justindevb.replay.recording;

import com.google.gson.*;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom Gson adapter that serializes and deserializes {@link TimelineEvent} records
 * to and from the same flat-JSON format that the original {@code Map<String, Object>}
 * approach produced.
 *
 * <h2>Why a custom adapter instead of raw {@code Map} serialization?</h2>
 * <ol>
 *   <li><b>Compile-time safety.</b> With raw maps, a typo like {@code event.get("worl")}
 *       silently returns {@code null} and causes a {@code NullPointerException} later.
 *       With typed records, the compiler catches it immediately.</li>
 *   <li><b>Backward compatibility.</b> This adapter reads and writes the exact same JSON
 *       format as before. Replay files saved by older plugin versions load correctly
 *       because the adapter provides sensible defaults for any fields that are absent
 *       in the JSON (e.g., {@code "pose"} was not recorded in early versions). This
 *       gives us a single, explicit place to handle migration — right here in the
 *       deserializer — instead of scattering null checks across dozens of call sites.</li>
 *   <li><b>Forward compatibility.</b> Adding a new field to a record only requires
 *       updating this adapter with a default value. Old replay files that lack the
 *       field will load with the default, and new files that include it will load
 *       normally. No file-format version markers are needed.</li>
 * </ol>
 *
 * <h2>JSON format contract</h2>
 * Every event is a flat JSON object with a {@code "type"} discriminator string.
 * The adapter preserves this contract exactly. Files produced by the typed system
 * are byte-compatible with the untyped system and vice versa.
 *
 * @see TimelineEvent
 */
public class TimelineEventAdapter implements JsonSerializer<TimelineEvent>, JsonDeserializer<TimelineEvent> {

    // ── Serialization ─────────────────────────────────────────

    @Override
    public JsonElement serialize(TimelineEvent event, Type typeOfSrc, JsonSerializationContext ctx) {
        JsonObject json = new JsonObject();
        switch (event) {
            case TimelineEvent.PlayerMove e -> {
                json.addProperty("tick", e.tick());
                json.addProperty("type", "player_move");
                json.addProperty("name", e.name());
                json.addProperty("etype", "PLAYER");
                json.addProperty("uuid", e.uuid());
                json.addProperty("world", e.world());
                json.addProperty("x", e.x());
                json.addProperty("y", e.y());
                json.addProperty("z", e.z());
                json.addProperty("yaw", e.yaw());
                json.addProperty("pitch", e.pitch());
                if (e.pose() != null) json.addProperty("pose", e.pose());
            }
            case TimelineEvent.EntityMove e -> {
                json.addProperty("tick", e.tick());
                json.addProperty("type", "entity_move");
                json.addProperty("uuid", e.uuid());
                json.addProperty("etype", e.etype());
                json.addProperty("world", e.world());
                json.addProperty("x", e.x());
                json.addProperty("y", e.y());
                json.addProperty("z", e.z());
                json.addProperty("yaw", e.yaw());
                json.addProperty("pitch", e.pitch());
            }
            case TimelineEvent.InventoryUpdate e -> {
                json.addProperty("tick", e.tick());
                json.addProperty("type", "inventory_update");
                json.addProperty("uuid", e.uuid());
                json.addProperty("mainHand", e.mainHand());
                json.addProperty("offHand", e.offHand());
                json.add("armor", ctx.serialize(e.armor()));
                json.add("contents", ctx.serialize(e.contents()));
            }
            case TimelineEvent.BlockBreak e -> {
                json.addProperty("tick", e.tick());
                json.addProperty("type", "block_break");
                json.addProperty("uuid", e.uuid());
                json.addProperty("world", e.world());
                json.addProperty("x", e.x());
                json.addProperty("y", e.y());
                json.addProperty("z", e.z());
                json.addProperty("blockData", e.blockData());
            }
            case TimelineEvent.BlockBreakComplete e -> {
                json.addProperty("tick", e.tick());
                json.addProperty("type", "block_break_complete");
                json.addProperty("uuid", e.uuid());
                json.addProperty("world", e.world());
                json.addProperty("x", e.x());
                json.addProperty("y", e.y());
                json.addProperty("z", e.z());
            }
            case TimelineEvent.BlockBreakStage e -> {
                json.addProperty("tick", e.tick());
                json.addProperty("type", "block_break_stage");
                if (e.uuid() != null) json.addProperty("uuid", e.uuid());
                json.addProperty("world", e.world());
                json.addProperty("x", e.x());
                json.addProperty("y", e.y());
                json.addProperty("z", e.z());
                json.addProperty("stage", e.stage());
            }
            case TimelineEvent.BlockPlace e -> {
                json.addProperty("tick", e.tick());
                json.addProperty("type", "block_place");
                json.addProperty("uuid", e.uuid());
                json.addProperty("world", e.world());
                json.addProperty("x", e.x());
                json.addProperty("y", e.y());
                json.addProperty("z", e.z());
                json.addProperty("blockData", e.blockData());
                json.addProperty("replacedBlockData", e.replacedBlockData());
            }
            case TimelineEvent.ItemDrop e -> {
                json.addProperty("tick", e.tick());
                json.addProperty("type", "item_drop");
                json.addProperty("uuid", e.uuid());
                json.addProperty("item", e.item());
                JsonObject loc = new JsonObject();
                loc.addProperty("world", e.locWorld());
                loc.addProperty("x", e.locX());
                loc.addProperty("y", e.locY());
                loc.addProperty("z", e.locZ());
                loc.addProperty("yaw", e.locYaw());
                loc.addProperty("pitch", e.locPitch());
                json.add("loc", loc);
            }
            case TimelineEvent.Attack e -> {
                json.addProperty("tick", e.tick());
                json.addProperty("type", "attack");
                json.addProperty("uuid", e.uuid());
                if (e.targetUuid() != null) json.addProperty("targetUuid", e.targetUuid());
                json.addProperty("entityUuid", e.entityUuid());
                json.addProperty("entityType", e.entityType());
            }
            case TimelineEvent.Swing e -> {
                json.addProperty("tick", e.tick());
                json.addProperty("type", "swing");
                json.addProperty("uuid", e.uuid());
                json.addProperty("hand", e.hand());
            }
            case TimelineEvent.SprintToggle e -> {
                json.addProperty("tick", e.tick());
                json.addProperty("type", e.sprinting() ? "sprint_start" : "sprint_stop");
                json.addProperty("uuid", e.uuid());
            }
            case TimelineEvent.SneakToggle e -> {
                json.addProperty("tick", e.tick());
                json.addProperty("type", e.sneaking() ? "sneak_start" : "sneak_stop");
                json.addProperty("uuid", e.uuid());
            }
            case TimelineEvent.Damaged e -> {
                json.addProperty("tick", e.tick());
                json.addProperty("type", "damaged");
                json.addProperty("uuid", e.uuid());
                json.addProperty("entityType", e.entityType());
                json.addProperty("cause", e.cause());
                json.addProperty("finalDamage", e.finalDamage());
            }
            case TimelineEvent.EntitySpawn e -> {
                json.addProperty("tick", e.tick());
                json.addProperty("type", "entity_spawn");
                json.addProperty("uuid", e.uuid());
                json.addProperty("etype", e.etype());
                json.addProperty("world", e.world());
                json.addProperty("x", e.x());
                json.addProperty("y", e.y());
                json.addProperty("z", e.z());
            }
            case TimelineEvent.EntityDeath e -> {
                json.addProperty("tick", e.tick());
                json.addProperty("type", "entity_death");
                json.addProperty("uuid", e.uuid());
                json.addProperty("etype", e.etype());
                json.addProperty("world", e.world());
                json.addProperty("x", e.x());
                json.addProperty("y", e.y());
                json.addProperty("z", e.z());
            }
            case TimelineEvent.PlayerQuit e -> {
                json.addProperty("tick", e.tick());
                json.addProperty("type", "player_quit");
                json.addProperty("uuid", e.uuid());
            }
        }
        return json;
    }

    // ── Deserialization ───────────────────────────────────────
    //
    // Each case provides sensible defaults for fields that may be absent in older
    // replay files. When a future version adds a new field to a record, add a
    // default here and old files will continue to load without any migration step.

    @Override
    public TimelineEvent deserialize(JsonElement jsonElement, Type typeOfT,
                                     JsonDeserializationContext ctx) throws JsonParseException {
        JsonObject obj = jsonElement.getAsJsonObject();
        String type = optString(obj, "type");
        if (type == null) throw new JsonParseException("Timeline event missing 'type' field");

        int tick = optInt(obj, "tick", 0);
        String uuid = optString(obj, "uuid");

        return switch (type) {
            case "player_move" -> new TimelineEvent.PlayerMove(
                    tick, uuid,
                    optString(obj, "name"),
                    optString(obj, "world"),
                    optDouble(obj, "x", 0), optDouble(obj, "y", 0), optDouble(obj, "z", 0),
                    optFloat(obj, "yaw", 0f), optFloat(obj, "pitch", 0f),
                    optString(obj, "pose")  // null in older recordings — adapter provides the default
            );
            case "entity_move" -> new TimelineEvent.EntityMove(
                    tick, uuid,
                    optString(obj, "etype"),
                    optString(obj, "world"),
                    optDouble(obj, "x", 0), optDouble(obj, "y", 0), optDouble(obj, "z", 0),
                    optFloat(obj, "yaw", 0f), optFloat(obj, "pitch", 0f)
            );
            case "inventory_update" -> new TimelineEvent.InventoryUpdate(
                    tick, uuid,
                    optString(obj, "mainHand"),
                    optString(obj, "offHand"),
                    readStringList(obj, "armor"),
                    readStringList(obj, "contents")
            );
            case "block_break" -> new TimelineEvent.BlockBreak(
                    tick, uuid,
                    optString(obj, "world"),
                    optInt(obj, "x", 0), optInt(obj, "y", 0), optInt(obj, "z", 0),
                    optString(obj, "blockData")
            );
            // "block_break_complete" is recorded when a player starts damaging a block;
            // "block_break_start" is an alias accepted for backward compatibility.
            case "block_break_complete", "block_break_start" -> new TimelineEvent.BlockBreakComplete(
                    tick, uuid,
                    optString(obj, "world"),
                    optInt(obj, "x", 0), optInt(obj, "y", 0), optInt(obj, "z", 0)
            );
            case "block_break_stage" -> new TimelineEvent.BlockBreakStage(
                    tick, uuid,
                    optString(obj, "world"),
                    optInt(obj, "x", 0), optInt(obj, "y", 0), optInt(obj, "z", 0),
                    optInt(obj, "stage", 0)
            );
            case "block_place" -> new TimelineEvent.BlockPlace(
                    tick, uuid,
                    optString(obj, "world"),
                    optInt(obj, "x", 0), optInt(obj, "y", 0), optInt(obj, "z", 0),
                    optString(obj, "blockData"),
                    optString(obj, "replacedBlockData")
            );
            case "item_drop" -> {
                // The nested location object was stored under "loc" in recording
                // but some versions may have used "location" — accept both.
                JsonObject loc = obj.has("loc") ? obj.getAsJsonObject("loc")
                        : obj.has("location") ? obj.getAsJsonObject("location")
                        : null;
                yield new TimelineEvent.ItemDrop(
                        tick, uuid,
                        optString(obj, "item"),
                        loc != null ? optString(loc, "world") : null,
                        loc != null ? optDouble(loc, "x", 0) : 0,
                        loc != null ? optDouble(loc, "y", 0) : 0,
                        loc != null ? optDouble(loc, "z", 0) : 0,
                        loc != null ? optFloat(loc, "yaw", 0f) : 0f,
                        loc != null ? optFloat(loc, "pitch", 0f) : 0f
                );
            }
            case "attack" -> new TimelineEvent.Attack(
                    tick, uuid,
                    optString(obj, "targetUuid"),
                    optString(obj, "entityUuid"),
                    optString(obj, "entityType")
            );
            case "swing" -> new TimelineEvent.Swing(tick, uuid, optString(obj, "hand"));
            case "sprint_start" -> new TimelineEvent.SprintToggle(tick, uuid, true);
            case "sprint_stop" -> new TimelineEvent.SprintToggle(tick, uuid, false);
            case "sneak_start" -> new TimelineEvent.SneakToggle(tick, uuid, true);
            case "sneak_stop" -> new TimelineEvent.SneakToggle(tick, uuid, false);
            case "damaged" -> new TimelineEvent.Damaged(
                    tick, uuid,
                    optString(obj, "entityType"),
                    optString(obj, "cause"),
                    optDouble(obj, "finalDamage", 0)
            );
            // Accept both "entity_spawn" and legacy "mob_spawn" type strings.
            case "entity_spawn", "mob_spawn" -> new TimelineEvent.EntitySpawn(
                    tick, uuid,
                    optString(obj, "etype"),
                    optString(obj, "world"),
                    optDouble(obj, "x", 0), optDouble(obj, "y", 0), optDouble(obj, "z", 0)
            );
            case "entity_death" -> new TimelineEvent.EntityDeath(
                    tick, uuid,
                    optString(obj, "etype"),
                    optString(obj, "world"),
                    optDouble(obj, "x", 0), optDouble(obj, "y", 0), optDouble(obj, "z", 0)
            );
            case "player_quit" -> new TimelineEvent.PlayerQuit(tick, uuid);
            default -> throw new JsonParseException("Unknown timeline event type: " + type);
        };
    }

    // ── Helpers ───────────────────────────────────────────────

    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static int optInt(JsonObject obj, String key, int def) {
        return obj.has(key) ? obj.get(key).getAsInt() : def;
    }

    private static double optDouble(JsonObject obj, String key, double def) {
        return obj.has(key) ? obj.get(key).getAsDouble() : def;
    }

    private static float optFloat(JsonObject obj, String key, float def) {
        return obj.has(key) ? obj.get(key).getAsFloat() : def;
    }

    private static List<String> readStringList(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) return List.of();
        JsonArray arr = obj.getAsJsonArray(key);
        List<String> list = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            list.add(el.isJsonNull() ? null : el.getAsString());
        }
        return list;
    }
}
