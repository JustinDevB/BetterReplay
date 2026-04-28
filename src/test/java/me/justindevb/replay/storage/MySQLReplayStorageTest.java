package me.justindevb.replay.storage;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import io.papermc.paper.plugin.configuration.PluginMeta;
import me.justindevb.replay.Replay;
import me.justindevb.replay.api.ReplayExportQuery;
import me.justindevb.replay.debug.ReplayDumpQuery;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.binary.BinaryReplayStorageCodec;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MySQLReplayStorageTest {

    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private Statement statement;
    @Mock private PreparedStatement saveStatement;
    @Mock private PreparedStatement selectStatement;
    @Mock private ResultSet selectResultSet;
    @Mock private Replay plugin;
    @Mock private FileConfiguration config;
    @Mock private PluginMeta pluginMeta;
    @Mock private FoliaLib foliaLib;
    @Mock private PlatformScheduler scheduler;

    @TempDir
    File tempDir;

    private MySQLReplayStorage storage;
    private byte[] storedBytes;

    @BeforeEach
    void setUp() throws Exception {
        when(plugin.getDataFolder()).thenReturn(tempDir);
        when(plugin.getPluginMeta()).thenReturn(pluginMeta);
        when(pluginMeta.getVersion()).thenReturn("1.4.0");
        when(plugin.getFoliaLib()).thenReturn(foliaLib);
        when(foliaLib.getScheduler()).thenReturn(scheduler);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MySQLReplayStorageTest"));

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<WrappedTask> consumer = invocation.getArgument(0);
            WrappedTask task = mock(WrappedTask.class);
            consumer.accept(task);
            return CompletableFuture.completedFuture(null);
        }).when(scheduler).runAsync(any());

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class).trim();
            if (sql.startsWith("INSERT INTO replays")) {
                return saveStatement;
            }
            if (sql.startsWith("SELECT data FROM replays WHERE name=?")) {
                return selectStatement;
            }
            return mock(PreparedStatement.class);
        });

        when(selectStatement.executeQuery()).thenReturn(selectResultSet);
        when(saveStatement.executeUpdate()).thenAnswer(invocation -> {
            return 1;
        });
        storage = new MySQLReplayStorage(dataSource, plugin);
    }

    @Test
    void initCreatesOrWidensReplayBlobColumn() throws Exception {
        verify(statement, atLeastOnce()).executeUpdate(contains("CREATE TABLE IF NOT EXISTS replays"));
        verify(statement).executeUpdate("ALTER TABLE replays MODIFY COLUMN data LONGBLOB NOT NULL");
    }

    @Test
    void saveReplayStoresBinaryArchiveAndLoadReplayReadsItBack() throws Exception {
        List<TimelineEvent> timeline = sampleTimeline();

        storage.saveReplay("binary", timeline).get();

        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(saveStatement).setBytes(org.mockito.ArgumentMatchers.eq(2), dataCaptor.capture());
        storedBytes = dataCaptor.getValue();
        assertTrue(new BinaryReplayStorageCodec().canDecode("binary", storedBytes));

        when(selectResultSet.next()).thenReturn(true);
        when(selectResultSet.getBytes("data")).thenReturn(storedBytes);

        List<TimelineEvent> loaded = storage.loadReplay("binary").get();

        assertNotNull(loaded);
        assertEquals(3, loaded.size());
    }

    @Test
    void getReplayFileExportsSameBinaryArchiveBytes() throws Exception {
        byte[] archive = new BinaryReplayStorageCodec().finalizeReplay("export", sampleTimeline(), "1.4.0");
        when(selectResultSet.next()).thenReturn(true);
        when(selectResultSet.getBytes("data")).thenReturn(archive);

        File exported = storage.getReplayFile("export").get();

        assertNotNull(exported);
        assertArrayEquals(archive, Files.readAllBytes(exported.toPath()));
    }

    @Test
    void loadReplayKeepsLegacyJsonCompatibility() throws Exception {
        byte[] legacyJson = new JsonReplayStorageCodec().encodeTimeline(sampleTimeline(), "1.4.0");
        when(selectResultSet.next()).thenReturn(true);
        when(selectResultSet.getBytes("data")).thenReturn(legacyJson);

        List<TimelineEvent> loaded = storage.loadReplay("legacy").get();

        assertNotNull(loaded);
        assertEquals(3, loaded.size());
    }

    @Test
    void filteredExportUsesReplayQuery() throws Exception {
        byte[] archive = new BinaryReplayStorageCodec().finalizeReplay("export-filtered", sampleTimeline(), "1.4.0");
        when(selectResultSet.next()).thenReturn(true);
        when(selectResultSet.getBytes("data")).thenReturn(archive);

        File exported = storage.getReplayFile("export-filtered", new ReplayExportQuery(null, 5, 10)).get();
        assertEquals(new File(tempDir, "exports").getCanonicalFile(), exported.getParentFile().getCanonicalFile());
        List<TimelineEvent> filtered = new BinaryReplayStorageCodec().decodeTimeline(Files.readAllBytes(exported.toPath()), "1.4.0");

        assertEquals(2, filtered.size());
        assertEquals(5, filtered.get(0).tick());
        assertEquals(10, filtered.get(1).tick());
    }

    @Test
    void getReplayInfo_returnsTimestampCountsAndSizes() throws Exception {
        byte[] archive = new BinaryReplayStorageCodec().finalizeReplay("info", sampleTimeline(), "1.4.0", 123456789L);
        when(selectResultSet.next()).thenReturn(true);
        when(selectResultSet.getBytes("data")).thenReturn(archive);

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
    void dumpUsesTickRangeAndWritesToPluginDumpFolder() throws Exception {
        byte[] archive = new BinaryReplayStorageCodec().finalizeReplay("dump-filtered", sampleTimeline(), "1.4.0");
        when(selectResultSet.next()).thenReturn(true);
        when(selectResultSet.getBytes("data")).thenReturn(archive);

        File dumped = storage.getReplayDumpFile("dump-filtered", new ReplayDumpQuery(5, 10)).get();
        String dumpText = Files.readString(dumped.toPath());

        assertEquals(new File(tempDir, "dumps").getCanonicalFile(), dumped.getParentFile().getCanonicalFile());
        assertTrue(dumpText.contains("[tick=5]"));
        assertTrue(dumpText.contains("[tick=10]"));
        assertFalse(dumpText.contains("[tick=0]"));
    }

    private static List<TimelineEvent> sampleTimeline() {
        return List.of(
                new TimelineEvent.PlayerMove(0, "uuid-1", "Steve", "world", 1, 64, 3, 0, 0, "STANDING"),
                new TimelineEvent.BlockBreak(5, "uuid-1", "world", 10, 64, 20, "minecraft:stone"),
                new TimelineEvent.PlayerQuit(10, "uuid-1")
        );
    }
}