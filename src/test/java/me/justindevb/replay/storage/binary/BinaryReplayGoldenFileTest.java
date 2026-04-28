package me.justindevb.replay.storage.binary;

import com.google.gson.Gson;
import me.justindevb.replay.recording.TimelineEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryReplayGoldenFileTest {

    private static final Gson GSON = new Gson();
    private static final long MINIMAL_RECORDING_STARTED_AT = 1_700_000_000_000L;
    private final BinaryReplayStorageCodec codec = new BinaryReplayStorageCodec();

    @Test
    void decodesMinimalGoldenArchive() throws Exception {
        byte[] fixture = readFixture("goldens/minimal-v1.br");

        List<TimelineEvent> decoded = codec.decodeTimeline(fixture, "1.4.0");

        assertEquals(List.of(
                new TimelineEvent.PlayerMove(0, "uuid-1", "Steve", "world", 1, 64, 3, 0, 0, "STANDING"),
                new TimelineEvent.BlockBreak(5, "uuid-1", "world", 10, 64, 20, "minecraft:stone"),
                new TimelineEvent.PlayerQuit(10, "uuid-1")
        ), decoded);
    }

    @Test
    void decodesSeekGoldenArchiveAndRetainsIndexMetadata() throws Exception {
        byte[] fixture = readFixture("goldens/seek-v1.br");

        BinaryReplayStorageCodec.ParsedBinaryReplay replay = codec.openReplay(fixture, "1.4.0");

        assertTrue(replay.indexLoaded());
        assertEquals(4, replay.timeline().findEventIndexAtOrAfterTick(90));
        assertEquals(100, replay.timeline().get(4).tick());
    }

    @Test
    void goldenManifestCarriesExpectedMetadata() throws Exception {
        byte[] fixture = readFixture("goldens/minimal-v1.br");
        Map<String, byte[]> entries = readArchiveEntries(fixture);

        BinaryReplayManifest manifest = GSON.fromJson(
                new String(entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME), StandardCharsets.UTF_8),
                BinaryReplayManifest.class);

        assertEquals(BinaryReplayFormat.FORMAT_VERSION, manifest.formatVersion());
        assertEquals("1.4.0", manifest.recordedWithVersion());
        assertEquals("1.4.0", manifest.minimumViewerVersion());
        assertEquals(MINIMAL_RECORDING_STARTED_AT, manifest.recordingStartedAtEpochMillis());
        assertEquals(BinaryReplayFormat.PAYLOAD_CHECKSUM_ALGORITHM, manifest.payloadChecksumAlgorithm());
        assertNotNull(manifest.payloadChecksum());
    }

    private static byte[] readFixture(String resourcePath) throws IOException {
        try (InputStream in = BinaryReplayGoldenFileTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Missing fixture resource: " + resourcePath);
            return in.readAllBytes();
        }
    }

    private static Map<String, byte[]> readArchiveEntries(byte[] archiveBytes) throws IOException {
        java.util.Map<String, byte[]> entries = new java.util.HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(archiveBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return entries;
    }
}