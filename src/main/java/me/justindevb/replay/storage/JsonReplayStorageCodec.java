package me.justindevb.replay.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.recording.TimelineEventAdapter;
import me.justindevb.replay.util.VersionUtil;
import me.justindevb.replay.util.io.ReplayCompressor;
import me.justindevb.replay.util.io.SerializedItemData;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Current JSON replay codec extracted behind the storage abstraction seam.
 */
public final class JsonReplayStorageCodec implements ReplayStorageCodec {

    public static final String EXT_COMPRESSED = ".json.gz";
    public static final String EXT_UNCOMPRESSED = ".json";

    private final Gson gson;

    public JsonReplayStorageCodec() {
        this(new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeHierarchyAdapter(TimelineEvent.class, new TimelineEventAdapter())
                .create());
    }

    JsonReplayStorageCodec(Gson gson) {
        this.gson = gson;
    }

    @Override
    public ReplayFormat format() {
        return ReplayFormat.JSON;
    }

    @Override
    public boolean canDecode(String replayName, byte[] storedBytes) {
        try {
            String json = ReplayCompressor.decompressIfNeeded(storedBytes);
            String trimmed = json.stripLeading();
            return trimmed.startsWith("{") || trimmed.startsWith("[");
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    @Override
    public String fileExtension(boolean compressionEnabled) {
        return compressionEnabled ? EXT_COMPRESSED : EXT_UNCOMPRESSED;
    }

    @Override
    public boolean supportsCompression() {
        return true;
    }

    @Override
    public byte[] encodeTimeline(List<TimelineEvent> timeline, String pluginVersion) {
        String json = VersionUtil.wrapTimeline(gson, timeline, pluginVersion);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public List<TimelineEvent> decodeTimeline(byte[] storedBytes, String runningVersion) throws IOException {
        try {
            String json = ReplayCompressor.decompressIfNeeded(storedBytes);
            return parseTimeline(json, runningVersion);
        } catch (JsonParseException | VersionUtil.ReplayVersionMismatchException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IOException("Failed to decode JSON replay payload", ex);
        }
    }

    @Override
    public ReplayInspection inspectReplay(String replayName, byte[] storedBytes, String runningVersion) throws IOException {
        String json = ReplayCompressor.decompressIfNeeded(storedBytes);
        JsonElement root = JsonParser.parseString(json);
        String createdBy = null;
        String minVersion = null;
        if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            if (object.has("createdBy") && !object.get("createdBy").isJsonNull()) {
                createdBy = object.get("createdBy").getAsString();
            }
            if (object.has("minVersion") && !object.get("minVersion").isJsonNull()) {
                minVersion = object.get("minVersion").getAsString();
            }
        }

        List<TimelineEvent> timeline = parseTimeline(json, runningVersion);
        return ReplayInspectionBuilder.build(
                replayName,
                format(),
                storedBytes.length,
                storedBytes.length,
                json.getBytes(StandardCharsets.UTF_8).length,
                null,
                createdBy,
                minVersion,
                false,
                0,
                timeline);
    }

    @Override
    public File writeReplayFile(String replayName, byte[] storedBytes, String runningVersion) throws IOException {
        List<TimelineEvent> timeline = decodeTimeline(storedBytes, runningVersion);
        File tempFile = File.createTempFile("replay_" + replayName + "_", EXT_UNCOMPRESSED);
        tempFile.deleteOnExit();
        try (FileWriter writer = new FileWriter(tempFile)) {
            gson.toJson(timeline, writer);
        }
        return tempFile;
    }

    private List<TimelineEvent> parseTimeline(String json, String runningVersion) {
        JsonElement root = JsonParser.parseString(json);
        JsonArray timeline = extractTimeline(root, runningVersion);
        List<TimelineEvent> events = new ArrayList<>();
        Map<String, LegacyEquipmentState> legacyEquipmentStateByUuid = new HashMap<>();

        for (JsonElement element : timeline) {
            JsonObject object = element.getAsJsonObject();
            String type = optString(object, "type");
            if (type == null) {
                throw new JsonParseException("Timeline event missing 'type' field");
            }

            switch (type) {
                case "inventory_update" -> upgradeLegacyInventoryUpdate(object, events, legacyEquipmentStateByUuid);
                case "held_item_change" -> upgradeLegacyHeldItemChange(object, events, legacyEquipmentStateByUuid);
                default -> events.add(gson.fromJson(object, TimelineEvent.class));
            }
        }

        return List.copyOf(events);
    }

    private JsonArray extractTimeline(JsonElement root, String runningVersion) {
        if (root.isJsonArray()) {
            return root.getAsJsonArray();
        }

        JsonObject object = root.getAsJsonObject();
        if (object.has("minVersion") && !object.get("minVersion").isJsonNull()) {
            String required = object.get("minVersion").getAsString();
            if (!VersionUtil.isAtLeast(runningVersion, required)) {
                throw new VersionUtil.ReplayVersionMismatchException(required, runningVersion);
            }
        }

        JsonElement timeline = object.get("timeline");
        if (timeline == null && object.has("type")) {
            JsonArray singleEventTimeline = new JsonArray();
            singleEventTimeline.add(object);
            return singleEventTimeline;
        }
        if (timeline == null || !timeline.isJsonArray()) {
            throw new JsonParseException("Replay JSON missing timeline array");
        }
        return timeline.getAsJsonArray();
    }

    private void upgradeLegacyInventoryUpdate(
            JsonObject object,
            List<TimelineEvent> events,
            Map<String, LegacyEquipmentState> legacyEquipmentStateByUuid
    ) {
        int tick = optInt(object, "tick", 0);
        String uuid = optString(object, "uuid");
        List<String> legacyContents = readStringList(object, "contents");
        SerializedItemData mainHand = SerializedItemData.fromBase64(optString(object, "mainHand"));
        SerializedItemData offHand = SerializedItemData.fromBase64(optString(object, "offHand"));
        List<SerializedItemData> armor = normalizeArmor(readSerializedItemList(object, "armor"));
        LegacyEquipmentState previous = legacyEquipmentStateByUuid.get(uuid);
        int heldSlot = previous != null ? previous.heldSlot() : inferHeldSlot(legacyContents, mainHand);

        events.add(new TimelineEvent.EquipmentStateUpdate(
                tick,
                uuid,
                heldSlot,
                mainHand,
                offHand,
                armor
        ));
        events.add(new TimelineEvent.InventoryStorageUpdate(
                tick,
                uuid,
                convertLegacyStorage(legacyContents)
        ));

        legacyEquipmentStateByUuid.put(uuid, new LegacyEquipmentState(heldSlot, mainHand, offHand, armor));
    }

    private void upgradeLegacyHeldItemChange(
            JsonObject object,
            List<TimelineEvent> events,
            Map<String, LegacyEquipmentState> legacyEquipmentStateByUuid
    ) {
        int tick = optInt(object, "tick", 0);
        String uuid = optString(object, "uuid");
        LegacyEquipmentState previous = legacyEquipmentStateByUuid.getOrDefault(uuid, LegacyEquipmentState.empty());
        SerializedItemData mainHand = SerializedItemData.fromBase64(optString(object, "mainHand"));
        SerializedItemData offHand = SerializedItemData.fromBase64(optString(object, "offHand"));

        events.add(new TimelineEvent.EquipmentStateUpdate(
                tick,
                uuid,
                previous.heldSlot(),
                mainHand,
                offHand,
                previous.armor()
        ));

        legacyEquipmentStateByUuid.put(uuid, new LegacyEquipmentState(
                previous.heldSlot(),
                mainHand,
                offHand,
                previous.armor()
        ));
    }

    private static List<SerializedItemData> convertLegacyStorage(List<String> legacyContents) {
        List<SerializedItemData> storage = new ArrayList<>(36);
        int size = Math.min(36, legacyContents.size());
        for (int index = 0; index < size; index++) {
            storage.add(SerializedItemData.fromBase64(legacyContents.get(index)));
        }
        while (storage.size() < 36) {
            storage.add(SerializedItemData.empty());
        }
        return List.copyOf(storage);
    }

    private static List<SerializedItemData> normalizeArmor(List<SerializedItemData> armor) {
        List<SerializedItemData> normalized = new ArrayList<>(armor);
        while (normalized.size() < 4) {
            normalized.add(SerializedItemData.empty());
        }
        return List.copyOf(normalized.subList(0, 4));
    }

    private static int inferHeldSlot(List<String> legacyContents, SerializedItemData mainHand) {
        if (mainHand.isEmpty()) {
            return 0;
        }
        for (int slot = 0; slot < Math.min(9, legacyContents.size()); slot++) {
            if (mainHand.equals(SerializedItemData.fromBase64(legacyContents.get(slot)))) {
                return slot;
            }
        }
        return 0;
    }

    private static String optString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    private static int optInt(JsonObject object, String key, int defaultValue) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        return object.get(key).getAsInt();
    }

    private static List<String> readStringList(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray(key)) {
            values.add(element.isJsonNull() ? null : element.getAsString());
        }
        return Collections.unmodifiableList(values);
    }

    private static List<SerializedItemData> readSerializedItemList(JsonObject object, String key) {
        List<String> values = readStringList(object, key);
        List<SerializedItemData> items = new ArrayList<>(values.size());
        for (String value : values) {
            items.add(SerializedItemData.fromBase64(value));
        }
        return List.copyOf(items);
    }

    private record LegacyEquipmentState(
            int heldSlot,
            SerializedItemData mainHand,
            SerializedItemData offHand,
            List<SerializedItemData> armor
    ) {
        private static LegacyEquipmentState empty() {
            return new LegacyEquipmentState(
                    0,
                    SerializedItemData.empty(),
                    SerializedItemData.empty(),
                    List.of(
                            SerializedItemData.empty(),
                            SerializedItemData.empty(),
                            SerializedItemData.empty(),
                            SerializedItemData.empty()
                    )
            );
        }
    }
}
