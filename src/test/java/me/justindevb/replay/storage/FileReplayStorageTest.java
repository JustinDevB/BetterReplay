package me.justindevb.replay.storage;

import me.justindevb.replay.Replay;
import me.justindevb.replay.api.ReplayExportQuery;
import me.justindevb.replay.storage.ReplayInspection;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.binary.BinaryReplayStorageCodec;
import org.bukkit.configuration.file.FileConfiguration;
import io.papermc.paper.plugin.configuration.PluginMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileReplayStorageTest {

    @Mock private Replay plugin;
    @Mock private PluginMeta pluginMeta;

    @TempDir
    File tempDir;

    private FileReplayStorage storage;

    @BeforeEach
    void setUp() {
        when(plugin.getDataFolder()).thenReturn(tempDir);
        when(plugin.getPluginMeta()).thenReturn(pluginMeta);
        when(pluginMeta.getVersion()).thenReturn("1.4.0");
        storage = new FileReplayStorage(plugin);
    }

    private List<TimelineEvent> sampleTimeline() {
        return List.of(
                new TimelineEvent.PlayerMove(0, "uuid-1", "Steve", "world", 1, 64, 3, 0, 0, "STANDING"),
                new TimelineEvent.BlockBreak(5, "uuid-1", "world", 10, 64, 20, "minecraft:stone"),
                new TimelineEvent.PlayerQuit(10, "uuid-1")
        );
    }

    // ── Binary roundtrip ──────────────────────────────────────

    @Nested
    class BinaryMode {

        @Test
        void saveAndLoad_roundtrip() throws ExecutionException, InterruptedException {
            List<TimelineEvent> timeline = sampleTimeline();
            storage.saveReplay("test", timeline).get();

            List<TimelineEvent> loaded = storage.loadReplay("test").get();

            assertNotNull(loaded);
            assertEquals(timeline.size(), loaded.size());
            assertInstanceOf(TimelineEvent.PlayerMove.class, loaded.get(0));
            assertInstanceOf(TimelineEvent.BlockBreak.class, loaded.get(1));
            assertInstanceOf(TimelineEvent.PlayerQuit.class, loaded.get(2));
        }

        @Test
        void savedFile_hasGzExtension() throws ExecutionException, InterruptedException {
            storage.saveReplay("compressed", sampleTimeline()).get();

            File replayDir = new File(tempDir, "replays");
            File compressed = new File(replayDir, "compressed.br");
            assertTrue(compressed.exists());

            File plain = new File(replayDir, "compressed.json");
            assertFalse(plain.exists());
        }

        @Test
        void save_removesLegacyUncompressed() throws ExecutionException, InterruptedException, IOException {
            File replayDir = new File(tempDir, "replays");
            replayDir.mkdirs();
            File legacy = new File(replayDir, "migration.json");
            Files.writeString(legacy.toPath(), "[]");
            assertTrue(legacy.exists());

            storage.saveReplay("migration", sampleTimeline()).get();

            assertFalse(legacy.exists());
            assertTrue(new File(replayDir, "migration.br").exists());
        }
    }

    // ── Stable save format ────────────────────────────────────

    @Nested
    class StableSaveFormat {

        @Test
        void saveAndLoad_roundtrip() throws ExecutionException, InterruptedException {
            List<TimelineEvent> timeline = sampleTimeline();
            storage.saveReplay("plain", timeline).get();

            List<TimelineEvent> loaded = storage.loadReplay("plain").get();
            assertNotNull(loaded);
            assertEquals(timeline.size(), loaded.size());
        }

        @Test
        void savedFile_hasBinaryExtension() throws ExecutionException, InterruptedException {
            storage.saveReplay("plainfile", sampleTimeline()).get();

            File replayDir = new File(tempDir, "replays");
            assertTrue(new File(replayDir, "plainfile.br").exists());
            assertFalse(new File(replayDir, "plainfile.json.gz").exists());
            assertFalse(new File(replayDir, "plainfile.json").exists());
        }
    }

    // ── listReplays ───────────────────────────────────────────

    @Test
    void listReplays_bothExtensions() throws ExecutionException, InterruptedException, IOException {
        storage.saveReplay("compressed1", sampleTimeline()).get();
        storage.saveReplay("uncompressed1", sampleTimeline()).get();

        List<String> names = storage.listReplays().get();
        assertTrue(names.contains("compressed1"));
        assertTrue(names.contains("uncompressed1"));
        assertEquals(2, names.size());
    }

    @Test
    void listReplays_empty() throws ExecutionException, InterruptedException {
        assertTrue(storage.listReplays().get().isEmpty());
    }

    // ── deleteReplay ──────────────────────────────────────────

    @Test
    void deleteReplay_existing_returnsTrue() throws ExecutionException, InterruptedException {
        storage.saveReplay("todelete", sampleTimeline()).get();

        assertTrue(storage.deleteReplay("todelete").get());

        assertNull(storage.loadReplay("todelete").get());
    }

    @Test
    void deleteReplay_nonExistent_returnsFalse() throws ExecutionException, InterruptedException {
        assertFalse(storage.deleteReplay("nope").get());
    }

    // ── replayExists ──────────────────────────────────────────

    @Test
    void replayExists_true() throws ExecutionException, InterruptedException {
        storage.saveReplay("exists", sampleTimeline()).get();

        assertTrue(storage.replayExists("exists").get());
    }

    @Test
    void replayExists_false() throws ExecutionException, InterruptedException {
        assertFalse(storage.replayExists("nope").get());
    }

    // ── getReplayFile ─────────────────────────────────────────

    @Test
    void getReplayFile_existing() throws ExecutionException, InterruptedException {
        storage.saveReplay("filereq", sampleTimeline()).get();

        File file = storage.getReplayFile("filereq").get();
        assertNotNull(file);
        assertTrue(file.exists());
        assertTrue(file.getName().contains("filereq"));
    }

    @Test
    void getReplayFile_nonExistent_returnsNull() throws ExecutionException, InterruptedException {
        assertNull(storage.getReplayFile("nope").get());
    }

    @Test
    void getReplayInfo_returnsTimestampCountsAndSizes() throws Exception {
        storage.saveReplay("info", new ReplaySaveRequest(sampleTimeline(), 123456789L)).get();

        ReplayInspection info = storage.getReplayInfo("info").get();

        assertNotNull(info);
        assertEquals(3, info.recordCount());
        assertEquals(0, info.startTick());
        assertEquals(10, info.endTick());
        assertEquals(10, info.durationTicks());
        assertEquals(123456789L, info.recordingStartedAtEpochMillis());
        assertTrue(info.storedBytes() > 0);
        assertTrue(info.compressedPayloadBytes() > 0);
        assertTrue(info.decompressedPayloadBytes() > info.compressedPayloadBytes());
        assertTrue(info.indexedPayload());
    }

    @Test
    void getReplayDumpFile_writesBoundedHumanReadableFile() throws Exception {
        storage.saveReplay("dumped", sampleTimeline()).get();

        File dumped = storage.getReplayDumpFile("dumped", new me.justindevb.replay.debug.ReplayDumpQuery(5, 10)).get();
        String dumpText = Files.readString(dumped.toPath());

        assertEquals(new File(tempDir, "dumps").getCanonicalFile(), dumped.getParentFile().getCanonicalFile());
        assertTrue(dumpText.contains("[tick=5]"));
        assertTrue(dumpText.contains("[tick=10]"));
        assertFalse(dumpText.contains("[tick=0]"));
    }

    // ── loadReplay ────────────────────────────────────────────

    @Test
    void loadReplay_nonExistent_returnsNull() throws ExecutionException, InterruptedException {
        assertNull(storage.loadReplay("does-not-exist").get());
    }

    // ── Cross-format loading ──────────────────────────────────

    @Test
    void canLoadBinaryArchiveAfterSavingBinaryArchive() throws ExecutionException, InterruptedException {
        storage.saveReplay("crossformat", sampleTimeline()).get();

        List<TimelineEvent> loaded = storage.loadReplay("crossformat").get();
        assertNotNull(loaded);
        assertEquals(3, loaded.size());
    }

    @Test
    void keepsLegacyJsonLoadCompatibilityAlongsideBinarySaves() throws Exception {
        File replayDir = new File(tempDir, "replays");
        replayDir.mkdirs();
        byte[] legacyJson = new JsonReplayStorageCodec().encodeTimeline(sampleTimeline(), "1.4.0");
        Files.write(new File(replayDir, "legacy.json").toPath(), legacyJson);

        storage.saveReplay("binary-save", sampleTimeline()).get();

        List<TimelineEvent> legacyLoaded = storage.loadReplay("legacy").get();
        List<TimelineEvent> binaryLoaded = storage.loadReplay("binary-save").get();

        assertNotNull(legacyLoaded);
        assertEquals(3, legacyLoaded.size());
        assertNotNull(binaryLoaded);
        assertEquals(3, binaryLoaded.size());
        assertTrue(new File(replayDir, "binary-save.br").exists());
    }

    @Test
    void prefersBinaryArchiveWhenLegacyJsonAndBrBothExist() throws Exception {
        File replayDir = new File(tempDir, "replays");
        replayDir.mkdirs();

        byte[] legacyJson = new JsonReplayStorageCodec().encodeTimeline(List.of(new TimelineEvent.PlayerQuit(0, "legacy")), "1.4.0");
        Files.write(new File(replayDir, "mixed.json").toPath(), legacyJson);

        byte[] archive = new BinaryReplayStorageCodec().finalizeReplay("mixed", sampleTimeline(), "1.4.0");
        Files.write(new File(replayDir, "mixed.br").toPath(), archive);

        List<TimelineEvent> loaded = storage.loadReplay("mixed").get();

        assertNotNull(loaded);
        assertEquals(3, loaded.size());
        assertEquals("uuid-1", loaded.get(0).uuid());
    }

    @Test
    void listsMixedLegacyAndBinaryReplayOnlyOnce() throws Exception {
        File replayDir = new File(tempDir, "replays");
        replayDir.mkdirs();

        byte[] legacyJson = new JsonReplayStorageCodec().encodeTimeline(List.of(new TimelineEvent.PlayerQuit(0, "legacy")), "1.4.0");
        Files.write(new File(replayDir, "mixed.json").toPath(), legacyJson);

        byte[] archive = new BinaryReplayStorageCodec().finalizeReplay("mixed", sampleTimeline(), "1.4.0");
        Files.write(new File(replayDir, "mixed.br").toPath(), archive);

        List<String> names = storage.listReplays().get();

        assertEquals(1, names.stream().filter("mixed"::equals).count());
    }

    @Test
    void exportMatchesBinaryArchiveStoredOnDisk() throws Exception {
        storage.saveReplay("export-eq", sampleTimeline()).get();

        File replayDir = new File(tempDir, "replays");
        byte[] storedBytes = Files.readAllBytes(new File(replayDir, "export-eq.br").toPath());
        File exported = storage.getReplayFile("export-eq").get();

        assertNotNull(exported);
        assertArrayEquals(storedBytes, Files.readAllBytes(exported.toPath()));
    }

    @Test
    void filteredExportWritesBoundedBinaryArchive() throws Exception {
        storage.saveReplay("filtered", sampleTimeline()).get();

        File exported = storage.getReplayFile("filtered", new ReplayExportQuery(null, 5, 10)).get();
        assertEquals(new File(tempDir, "exports").getCanonicalFile(), exported.getParentFile().getCanonicalFile());
        List<TimelineEvent> filtered = new BinaryReplayStorageCodec().decodeTimeline(Files.readAllBytes(exported.toPath()), "1.4.0");

        assertEquals(2, filtered.size());
        assertEquals(5, filtered.get(0).tick());
        assertEquals(10, filtered.get(1).tick());
    }

    @Test
    void canLoadBinaryArchiveByReplayName() throws Exception {
        File replayDir = new File(tempDir, "replays");
        replayDir.mkdirs();
        byte[] archive = new BinaryReplayStorageCodec().finalizeReplay("binary", sampleTimeline(), "1.4.0");
        Files.write(new File(replayDir, "binary.br").toPath(), archive);

        List<TimelineEvent> loaded = storage.loadReplay("binary").get();

        assertNotNull(loaded);
        assertEquals(3, loaded.size());
        assertTrue(storage.listReplays().get().contains("binary"));
    }
}
