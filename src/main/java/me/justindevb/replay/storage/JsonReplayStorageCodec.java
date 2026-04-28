package me.justindevb.replay.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.recording.TimelineEventAdapter;
import me.justindevb.replay.util.VersionUtil;
import me.justindevb.replay.util.io.ReplayCompressor;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Current JSON replay codec extracted behind the storage abstraction seam.
 */
public final class JsonReplayStorageCodec implements ReplayStorageCodec {

    public static final String EXT_COMPRESSED = ".json.gz";
    public static final String EXT_UNCOMPRESSED = ".json";

    private static final Type TIMELINE_LIST_TYPE = new TypeToken<List<TimelineEvent>>() {}.getType();

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
            return VersionUtil.parseReplayJson(gson, json, runningVersion, TIMELINE_LIST_TYPE);
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

        List<TimelineEvent> timeline = VersionUtil.parseReplayJson(gson, json, runningVersion, TIMELINE_LIST_TYPE);
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
}