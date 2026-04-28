package me.justindevb.replay.storage.binary;

import com.google.gson.Gson;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.util.VersionUtil;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryReplayStorageCodecTest {

    private static final long RECORDING_STARTED_AT = 1_700_000_000_000L;

    private final BinaryReplayStorageCodec codec = new BinaryReplayStorageCodec();
    private final Gson gson = new Gson();

    @Test
    void loadsValidBinaryReplayArchive() throws Exception {
        byte[] archive = codec.finalizeReplay("valid", sampleTimeline(), "1.4.0", RECORDING_STARTED_AT);

        List<TimelineEvent> decoded = codec.decodeTimeline(archive, "1.4.0");

        assertEquals(3, decoded.size());
        assertInstanceOf(TimelineEvent.PlayerMove.class, decoded.get(0));
        assertInstanceOf(TimelineEvent.BlockBreak.class, decoded.get(1));
        assertInstanceOf(TimelineEvent.PlayerQuit.class, decoded.get(2));
    }

    @Test
    void rejectsReplaysThatRequireNewerViewerVersion() throws Exception {
        byte[] archive = codec.finalizeReplay("versioned", sampleTimeline(), "1.4.0", RECORDING_STARTED_AT);
        Map<String, byte[]> entries = readArchiveEntries(archive);
        BinaryReplayManifest manifest = gson.fromJson(new String(entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME), StandardCharsets.UTF_8),
                BinaryReplayManifest.class);

        entries.put(BinaryReplayFormat.MANIFEST_ENTRY_NAME, gson.toJson(new BinaryReplayManifest(
                manifest.formatVersion(),
                manifest.recordedWithVersion(),
                "9.0.0",
                manifest.recordingStartedAtEpochMillis(),
                manifest.payloadChecksum(),
                manifest.payloadChecksumAlgorithm())).getBytes(StandardCharsets.UTF_8));

        byte[] mutatedArchive = writeArchive(entries);

        assertThrows(VersionUtil.ReplayVersionMismatchException.class,
                () -> codec.decodeTimeline(mutatedArchive, "1.4.0"));
    }

    @Test
    void seeksFromNearestCheckpointAndDecodesForward() throws Exception {
        List<TimelineEvent> timeline = new ArrayList<>();
        for (int tick = 0; tick <= 150; tick += 25) {
            timeline.add(new TimelineEvent.PlayerQuit(tick, "uuid-" + tick));
        }

        byte[] archive = codec.finalizeReplay("seek", timeline, "1.4.0", RECORDING_STARTED_AT);
        BinaryReplayStorageCodec.ParsedBinaryReplay replay = codec.openReplay(archive, "1.4.0");

        int index = replay.timeline().findEventIndexAtOrAfterTick(90);

        assertEquals(4, index);
        assertEquals(100, replay.timeline().get(index).tick());
    }

    @Test
    void fallsBackToScanningWhenTickIndexIsAbsent() throws Exception {
        byte[] archive = codec.finalizeReplay("fallback", sampleTimeline(), "1.4.0", RECORDING_STARTED_AT);
        Map<String, byte[]> entries = readArchiveEntries(archive);
        byte[] payload = decompress(entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME));
        long indexOffset = ByteBuffer.wrap(payload, payload.length - BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES,
                        BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .getLong();

        byte[] payloadWithoutIndex = Arrays.copyOfRange(payload, 0, Math.toIntExact(indexOffset));
        entries.put(BinaryReplayFormat.REPLAY_ENTRY_NAME, compress(payloadWithoutIndex));
        updateManifestChecksum(entries);

        BinaryReplayStorageCodec.ParsedBinaryReplay replay = codec.openReplay(writeArchive(entries), "1.4.0");

        assertFalse(replay.indexLoaded());
        assertEquals(3, replay.timeline().size());
        assertEquals(3, replay.timeline().findEventIndexAtOrAfterTick(100));
    }

    @Test
    void failsOnUnknownRecordTags() throws Exception {
        byte[] archive = codec.finalizeReplay("unknown-tag", sampleTimeline(), "1.4.0", RECORDING_STARTED_AT);
        Map<String, byte[]> entries = readArchiveEntries(archive);
        byte[] payload = decompress(entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME));

        int firstRecordLengthSize = 1;
        int firstRecordTypeOffset = BinaryReplayFormat.PAYLOAD_HEADER_SIZE + firstRecordLengthSize;
        payload[firstRecordTypeOffset] = 0x7F;

        entries.put(BinaryReplayFormat.REPLAY_ENTRY_NAME, compress(payload));
        updateManifestChecksum(entries);

        assertThrows(IOException.class, () -> codec.decodeTimeline(writeArchive(entries), "1.4.0"));
    }

    private static List<TimelineEvent> sampleTimeline() {
        return List.of(
                new TimelineEvent.PlayerMove(0, "uuid-1", "Steve", "world", 1, 64, 3, 0, 0, "STANDING"),
                new TimelineEvent.BlockBreak(5, "uuid-1", "world", 10, 64, 20, "minecraft:stone"),
                new TimelineEvent.PlayerQuit(10, "uuid-1")
        );
    }

    private static Map<String, byte[]> readArchiveEntries(byte[] archiveBytes) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archiveBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static byte[] writeArchive(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            writeStoredEntry(zip, BinaryReplayFormat.MANIFEST_ENTRY_NAME, entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME));
            writeStoredEntry(zip, BinaryReplayFormat.REPLAY_ENTRY_NAME, entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME));
        }
        return out.toByteArray();
    }

    private static void writeStoredEntry(ZipOutputStream zip, String name, byte[] contents) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(contents.length);
        entry.setCompressedSize(contents.length);
        CRC32 crc32 = new CRC32();
        crc32.update(contents, 0, contents.length);
        entry.setCrc(crc32.getValue());
        zip.putNextEntry(entry);
        zip.write(contents);
        zip.closeEntry();
    }

    private static byte[] decompress(byte[] replayBytes) throws IOException {
        try (LZ4FrameInputStream lz4 = new LZ4FrameInputStream(new ByteArrayInputStream(replayBytes))) {
            return lz4.readAllBytes();
        }
    }

    private static byte[] compress(byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(out)) {
            lz4.write(payload);
        }
        return out.toByteArray();
    }

    private void updateManifestChecksum(Map<String, byte[]> entries) {
        BinaryReplayManifest manifest = gson.fromJson(new String(entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME), StandardCharsets.UTF_8),
                BinaryReplayManifest.class);
        CRC32C crc32c = new CRC32C();
        byte[] replayBytes = entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME);
        crc32c.update(replayBytes, 0, replayBytes.length);
        BinaryReplayManifest updated = new BinaryReplayManifest(
                manifest.formatVersion(),
                manifest.recordedWithVersion(),
                manifest.minimumViewerVersion(),
            manifest.recordingStartedAtEpochMillis(),
                "%08x".formatted(crc32c.getValue()),
                manifest.payloadChecksumAlgorithm());
        entries.put(BinaryReplayFormat.MANIFEST_ENTRY_NAME, gson.toJson(updated).getBytes(StandardCharsets.UTF_8));
    }
}