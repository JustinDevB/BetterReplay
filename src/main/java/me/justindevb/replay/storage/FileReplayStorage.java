package me.justindevb.replay.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import me.justindevb.replay.Replay;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.recording.TimelineEventAdapter;
import me.justindevb.replay.util.io.ReplayCompressor;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class FileReplayStorage implements ReplayStorage {

    private static final String EXT_COMPRESSED   = ".json.gz";
    private static final String EXT_UNCOMPRESSED = ".json";

    private final File replayFolder;
    private final Replay replay;
    private static final Type TIMELINE_LIST_TYPE = new TypeToken<List<TimelineEvent>>() {}.getType();
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeHierarchyAdapter(TimelineEvent.class, new TimelineEventAdapter())
            .create();

    public FileReplayStorage(Replay replay) {
        this.replay = replay;
        this.replayFolder = new File(replay.getDataFolder(), "replays");
        if (!replayFolder.exists())
            replayFolder.mkdirs();
    }

    /** Returns true when the plugin config has compression enabled (default: true). */
    private boolean isCompressionEnabled() {
        return replay.getConfig().getBoolean("General.Compress-Replays", true);
    }

    /**
     * Resolve the existing file for a replay name, checking the compressed
     * extension first, then the legacy uncompressed extension.
     * Returns null when neither file exists.
     */
    private File resolveExisting(String name) {
        File compressed = new File(replayFolder, name + EXT_COMPRESSED);
        if (compressed.exists()) return compressed;
        File plain = new File(replayFolder, name + EXT_UNCOMPRESSED);
        if (plain.exists()) return plain;
        return null;
    }

    @Override
    public CompletableFuture<Void> saveReplay(String name, List<TimelineEvent> timeline) {
        return CompletableFuture.runAsync(() -> {
            try {
                String json = gson.toJson(timeline);
                if (isCompressionEnabled()) {
                    File file = new File(replayFolder, name + EXT_COMPRESSED);
                    Files.write(file.toPath(), ReplayCompressor.compress(json));
                    // Remove any legacy uncompressed file with the same name
                    File legacy = new File(replayFolder, name + EXT_UNCOMPRESSED);
                    if (legacy.exists()) legacy.delete();
                } else {
                    File file = new File(replayFolder, name + EXT_UNCOMPRESSED);
                    try (FileWriter writer = new FileWriter(file)) {
                        writer.write(json);
                    }
                    // Remove any compressed file with the same name
                    File compressed = new File(replayFolder, name + EXT_COMPRESSED);
                    if (compressed.exists()) compressed.delete();
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to save replay " + name, e);
            }
        });
    }

    @Override
    public CompletableFuture<List<TimelineEvent>> loadReplay(String name) {
        return CompletableFuture.supplyAsync(() -> {
            File file = resolveExisting(name);
            if (file == null) return null;

            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                // Auto-detect: works for both compressed and plain-text files
                String json = ReplayCompressor.decompressIfNeeded(bytes);
                return gson.fromJson(json, TIMELINE_LIST_TYPE);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load replay " + name, e);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> deleteReplay(String name) {
        return CompletableFuture.supplyAsync(() -> {
            File file = resolveExisting(name);
            return file != null && file.delete();
        });
    }

    @Override
    public CompletableFuture<List<String>> listReplays() {
        return CompletableFuture.supplyAsync(() -> {
            File[] files = replayFolder.listFiles(
                    (dir, n) -> n.endsWith(EXT_COMPRESSED) || n.endsWith(EXT_UNCOMPRESSED));
            List<String> names = new ArrayList<>();
            if (files != null) {
                for (File f : files) {
                    String n = f.getName();
                    if (n.endsWith(EXT_COMPRESSED)) {
                        names.add(n.substring(0, n.length() - EXT_COMPRESSED.length()));
                    } else {
                        names.add(n.substring(0, n.length() - EXT_UNCOMPRESSED.length()));
                    }
                }
            }
            return names;
        });
    }

    @Override
    public CompletableFuture<Boolean> replayExists(String name) {
        return CompletableFuture.supplyAsync(() -> resolveExisting(name) != null);
    }

    @Override
    public CompletableFuture<File> getReplayFile(String name) {
        return CompletableFuture.supplyAsync(() -> {
            File file = resolveExisting(name);
            return (file != null && file.isFile()) ? file : null;
        });
    }
}
