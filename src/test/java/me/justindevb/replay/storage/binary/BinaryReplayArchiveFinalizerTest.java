package me.justindevb.replay.storage.binary;

import com.google.gson.Gson;
import me.justindevb.replay.recording.TimelineEvent;
import net.jpountz.lz4.LZ4FrameInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32C;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryReplayArchiveFinalizerTest {

    private static final long RECORDING_STARTED_AT = 1_700_000_000_000L;

    private final BinaryReplayArchiveFinalizer finalizer = new BinaryReplayArchiveFinalizer();
    private final Gson gson = new Gson();

    @TempDir
    Path tempDir;

    @Test
    void finalizesCleanReplayIntoStoredArchive() throws Exception {
        List<TimelineEvent> timeline = List.of(
                new TimelineEvent.PlayerQuit(0, "uuid-1"),
                new TimelineEvent.SprintToggle(55, "uuid-1", true));

        byte[] archive = finalizer.finalizeReplay("clean", timeline, "1.4.0", RECORDING_STARTED_AT);
        Map<String, byte[]> entries = readArchiveEntries(archive);

        assertTrue(entries.containsKey(BinaryReplayFormat.MANIFEST_ENTRY_NAME));
        assertTrue(entries.containsKey(BinaryReplayFormat.REPLAY_ENTRY_NAME));

        byte[] payload = decompress(entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME));
        assertArrayEquals(BinaryReplayFormat.payloadMagicBytes(), java.util.Arrays.copyOfRange(payload, 0, 4));
        assertEquals(BinaryReplayFormat.FORMAT_VERSION, payload[4] & 0xFF);
    }

    @Test
    void rebuildsMissingIndexFromRecoveredPrefix() throws Exception {
        List<TimelineEvent> timeline = List.of(
                new TimelineEvent.PlayerQuit(0, "uuid-1"),
                new TimelineEvent.PlayerQuit(75, "uuid-2"));

        byte[] archive = finalizer.finalizeReplay("index", timeline, "1.4.0", RECORDING_STARTED_AT);
        byte[] payload = decompress(readArchiveEntries(archive).get(BinaryReplayFormat.REPLAY_ENTRY_NAME));
        ParsedPayload parsedPayload = parsePayload(payload);

        assertEquals(2, parsedPayload.tickIndex().size());
        assertEquals(new BinaryTickIndexEntry(0, parsedPayload.tickIndex().get(0).byteOffset()), parsedPayload.tickIndex().get(0));
        assertEquals(50, parsedPayload.tickIndex().get(1).tick());
    }

    @Test
    void validatesManifestChecksumAgainstStoredReplayBytes() throws Exception {
        List<TimelineEvent> timeline = List.of(new TimelineEvent.PlayerQuit(0, "uuid-1"));

        byte[] archive = finalizer.finalizeReplay("checksum", timeline, "1.4.0", RECORDING_STARTED_AT);
        Map<String, byte[]> entries = readArchiveEntries(archive);
        BinaryReplayManifest manifest = gson.fromJson(new String(entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME), StandardCharsets.UTF_8),
                BinaryReplayManifest.class);

        CRC32C crc32c = new CRC32C();
        byte[] replayBytes = entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME);
        crc32c.update(replayBytes, 0, replayBytes.length);

        assertEquals("%08x".formatted(crc32c.getValue()), manifest.payloadChecksum());
        assertEquals(BinaryReplayFormat.PAYLOAD_CHECKSUM_ALGORITHM, manifest.payloadChecksumAlgorithm());
        assertEquals(RECORDING_STARTED_AT, manifest.recordingStartedAtEpochMillis());
    }

    @Test
    void storesLongRecordingOffsetsAtFiftyTickIntervals() throws Exception {
        List<TimelineEvent> timeline = new ArrayList<>();
        for (int tick = 0; tick <= 200; tick += 25) {
            timeline.add(new TimelineEvent.PlayerQuit(tick, "uuid-" + tick));
        }

        byte[] archive = finalizer.finalizeReplay("long", timeline, "1.4.0", RECORDING_STARTED_AT);
        ParsedPayload parsedPayload = parsePayload(decompress(readArchiveEntries(archive).get(BinaryReplayFormat.REPLAY_ENTRY_NAME)));

        assertEquals(List.of(0, 50, 100, 150, 200), parsedPayload.tickIndex().stream().map(BinaryTickIndexEntry::tick).toList());
        assertTrue(parsedPayload.tickIndex().stream().allMatch(entry -> entry.byteOffset() >= BinaryReplayFormat.PAYLOAD_HEADER_SIZE));
        assertTrue(parsedPayload.tickIndex().stream().map(BinaryTickIndexEntry::byteOffset).allMatch(parsedPayload.recordOffsets()::contains));
    }

    @Test
    void finalizesRecoveredPrefixAfterTailLoss() throws Exception {
        Path path = tempDir.resolve("recovered.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path, RECORDING_STARTED_AT)) {
            writer.append(new TimelineEvent.PlayerQuit(0, "uuid-1"));
            writer.append(new TimelineEvent.PlayerQuit(1, "uuid-2"));
            writer.flush();
        }

        byte[] bytes = java.nio.file.Files.readAllBytes(path);
        java.nio.file.Files.write(path, java.util.Arrays.copyOf(bytes, bytes.length - 2));
        BinaryReplayAppendLogRecovery recovery = reader.recover(path);

        byte[] archive = finalizer.finalizeRecoveredReplay("recovered", recovery, "1.4.0");
        Map<String, byte[]> entries = readArchiveEntries(archive);

        assertFalse(entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME).length == 0);
        assertEquals(BinaryReplayRecoveryStopReason.TRUNCATED_RECORD, recovery.stopReason());
        assertEquals(RECORDING_STARTED_AT, recovery.header().recordingStartedAtEpochMillis());
    }

    private static Map<String, byte[]> readArchiveEntries(byte[] archiveBytes) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archiveBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                zip.transferTo(out);
                entries.put(entry.getName(), out.toByteArray());
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static byte[] decompress(byte[] compressed) throws IOException {
        try (LZ4FrameInputStream in = new LZ4FrameInputStream(new ByteArrayInputStream(compressed))) {
            return in.readAllBytes();
        }
    }

    private static ParsedPayload parsePayload(byte[] payload) {
        long indexSectionOffset = ByteBuffer.wrap(
                payload,
                payload.length - BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES,
                BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .getLong();
        assertTrue(indexSectionOffset >= BinaryReplayFormat.PAYLOAD_HEADER_SIZE);

        List<Long> recordOffsets = new ArrayList<>();
        int offset = BinaryReplayFormat.PAYLOAD_HEADER_SIZE;
        while (offset < indexSectionOffset) {
            long recordOffset = offset;
            VarIntRead recordLengthRead = readVarInt(payload, offset);
            int recordLength = recordLengthRead.value();
            offset = recordLengthRead.nextOffset() + recordLength;
            recordOffsets.add(recordOffset);
        }

        byte[] indexSection = java.util.Arrays.copyOfRange(
            payload,
            (int) indexSectionOffset,
            payload.length - BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES);
        BinaryReplayAppendLogCodec.Cursor indexCursor = new BinaryReplayAppendLogCodec.Cursor(indexSection);
        assertArrayEquals(BinaryReplayFormat.indexSectionMagicBytes(), indexCursor.readBytes(BinaryReplayFormat.INDEX_SECTION_MAGIC.length));
        int stringCount = indexCursor.readVarInt();
        List<String> stringTable = new ArrayList<>(stringCount);
        for (int index = 0; index < stringCount; index++) {
            stringTable.add(indexCursor.readLengthPrefixedString().value());
        }
        int tickIndexSize = indexCursor.readVarInt();
        List<BinaryTickIndexEntry> tickIndex = new ArrayList<>(tickIndexSize);
        for (int index = 0; index < tickIndexSize; index++) {
            tickIndex.add(new BinaryTickIndexEntry(indexCursor.readInt(), indexCursor.readLong()));
        }
        return new ParsedPayload(stringTable, tickIndex, recordOffsets);
    }

    private static VarIntRead readVarInt(byte[] bytes, int offset) {
        int value = 0;
        int shift = 0;
        int currentOffset = offset;
        while (currentOffset < bytes.length) {
            int current = bytes[currentOffset++] & 0xFF;
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return new VarIntRead(value, currentOffset);
            }
            shift += 7;
        }
        throw new IllegalArgumentException("Unexpected end of payload while reading varint");
    }

    private record ParsedPayload(List<String> stringTable, List<BinaryTickIndexEntry> tickIndex, List<Long> recordOffsets) {
    }

    private record VarIntRead(int value, int nextOffset) {
    }
}