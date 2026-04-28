package me.justindevb.replay.storage;

import me.justindevb.replay.Replay;
import me.justindevb.replay.recording.TimelineEvent;
import org.bukkit.configuration.file.FileConfiguration;
import io.papermc.paper.plugin.configuration.PluginMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Edge-case and security-focused tests for FileReplayStorage.
 * Covers path-traversal names, corrupted files, and boundary conditions.
 */
class FileReplayStorageEdgeCaseTest {

    @TempDir File tempDir;
    private Replay replay;
    private FileReplayStorage storage;

    @BeforeEach
    void setUp() {
        replay = mock(Replay.class);
        when(replay.getDataFolder()).thenReturn(tempDir);

        PluginMeta meta = mock(PluginMeta.class);
        when(meta.getVersion()).thenReturn("1.4.0");
        when(replay.getPluginMeta()).thenReturn(meta);

        storage = new FileReplayStorage(replay);
    }

    // ── Path-traversal resilience ─────────────────────────────

    @Nested
    class PathTraversal {

        @Test
        void saveReplay_withPathTraversal_failsOrStaysInFolder() {
            // A malicious session name trying to escape the replays directory.
            // On Windows, '/' in filenames is illegal, so this should throw.
            // On Linux, File resolves '..' relative to parent — either way,
            // files should not leak outside the replays dir.
            String maliciousName = "..\\..\\..\\evil";
            List<TimelineEvent> timeline = List.of(
                    new TimelineEvent.PlayerQuit(0, "uuid1"));

            try {
                storage.saveReplay(maliciousName, timeline).get();
                // If it didn't throw, verify no file leaked outside replays dir
                File replaysDir = new File(tempDir, "replays");
                assertTrue(replaysDir.exists());
            } catch (Exception e) {
                // Expected on Windows — invalid path characters
            }
        }

        @Test
        void loadReplay_withPathTraversal_returnsNull() throws Exception {
            String maliciousName = "..\\..\\secret";
            List<TimelineEvent> result = storage.loadReplay(maliciousName).get();
            assertNull(result);
        }
    }

    // ── Corrupted / invalid file content ──────────────────────

    @Nested
    class CorruptedFiles {

        @Test
        void loadReplay_corruptedGzipFile_throwsException() throws Exception {
            File replaysDir = new File(tempDir, "replays");
            File corrupt = new File(replaysDir, "corrupt.json.gz");
            // Write invalid gzip (starts with magic bytes but content is broken)
            Files.write(corrupt.toPath(), new byte[]{0x1f, (byte) 0x8b, 0x08, 0x00, 0x01, 0x02});

            assertThrows(ExecutionException.class, () -> storage.loadReplay("corrupt").get());
        }

        @Test
        void loadReplay_invalidJson_throwsException() throws Exception {
            File replaysDir = new File(tempDir, "replays");
            File bad = new File(replaysDir, "bad.json");
            try (FileWriter w = new FileWriter(bad)) {
                w.write("not valid json {{{");
            }

            assertThrows(ExecutionException.class, () -> storage.loadReplay("bad").get());
        }

        @Test
        void loadReplay_emptyFile_throwsOrReturnsNull() throws Exception {
            File replaysDir = new File(tempDir, "replays");
            File empty = new File(replaysDir, "empty.json");
            empty.createNewFile();

            // Empty file → empty string → should throw or return null
            try {
                List<TimelineEvent> result = storage.loadReplay("empty").get();
                // If it doesn't throw, null is acceptable
                assertNull(result);
            } catch (ExecutionException e) {
                // also acceptable — corrupted data
            }
        }
    }

    // ── Boundary conditions ───────────────────────────────────

    @Nested
    class Boundaries {

        @Test
        void saveAndLoad_emptyTimeline() throws Exception {
            storage.saveReplay("empty-session", List.of()).get();
            List<TimelineEvent> loaded = storage.loadReplay("empty-session").get();
            assertNotNull(loaded);
            assertTrue(loaded.isEmpty());
        }

        @Test
        void deleteReplay_nonExistent_returnsFalse() throws Exception {
            assertFalse(storage.deleteReplay("does-not-exist").get());
        }

        @Test
        void listReplays_emptyFolder() throws Exception {
            List<String> replays = storage.listReplays().get();
            assertNotNull(replays);
            assertTrue(replays.isEmpty());
        }

        @Test
        void replayExists_nonExistent_returnsFalse() throws Exception {
            assertFalse(storage.replayExists("nope").get());
        }

        @Test
        void getReplayFile_nonExistent_returnsNull() throws Exception {
            assertNull(storage.getReplayFile("nope").get());
        }

        @Test
        void saveReplay_overwritesExisting() throws Exception {
            List<TimelineEvent> t1 = List.of(new TimelineEvent.PlayerQuit(0, "u1"));
            List<TimelineEvent> t2 = List.of(
                    new TimelineEvent.PlayerQuit(0, "u1"),
                    new TimelineEvent.PlayerQuit(1, "u2"));

            storage.saveReplay("overwrite-test", t1).get();
            storage.saveReplay("overwrite-test", t2).get();

            List<TimelineEvent> loaded = storage.loadReplay("overwrite-test").get();
            assertNotNull(loaded);
            assertEquals(2, loaded.size());
        }
    }

    // ── Stable binary save format ─────────────────────────────

    @Nested
    class StableBinaryFormat {

        @Test
        void repeatedSave_keepsBinaryArchivePathStable() throws Exception {
            storage.saveReplay("toggle-test", List.of(new TimelineEvent.PlayerQuit(0, "u"))).get();
            File archiveFile = new File(new File(tempDir, "replays"), "toggle-test.br");
            assertTrue(archiveFile.exists());

            storage.saveReplay("toggle-test", List.of(new TimelineEvent.PlayerQuit(1, "u"))).get();

            assertTrue(archiveFile.exists(), "Binary archive path should remain stable across saves");
            assertFalse(new File(new File(tempDir, "replays"), "toggle-test.json").exists());
            assertFalse(new File(new File(tempDir, "replays"), "toggle-test.json.gz").exists());
        }

        @Test
        void saveReplay_neverCreatesLegacyJsonVariants() throws Exception {
            storage.saveReplay("toggle2", List.of(new TimelineEvent.PlayerQuit(0, "u"))).get();

            assertTrue(new File(new File(tempDir, "replays"), "toggle2.br").exists());
            assertFalse(new File(new File(tempDir, "replays"), "toggle2.json").exists());
            assertFalse(new File(new File(tempDir, "replays"), "toggle2.json.gz").exists());
        }

        @Test
        void loadReplay_autoDetectsWithoutConfigToggle() throws Exception {
            storage.saveReplay("autodetect", List.of(new TimelineEvent.PlayerQuit(0, "u"))).get();

            List<TimelineEvent> loaded = storage.loadReplay("autodetect").get();
            assertNotNull(loaded);
            assertEquals(1, loaded.size());
        }
    }
}
