package me.justindevb.replay.storage.binary;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.justindevb.replay.chunk.CapturedChunkBaseline;
import me.justindevb.replay.chunk.ChunkCoordinate;
import me.justindevb.replay.chunk.ChunkRecordingArtifacts;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.ReplaySaveRequest;
import me.justindevb.replay.util.VersionUtil;
import me.justindevb.replay.util.io.SerializedItemData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryReplayStorageCodecTest {

    private static final long RECORDING_STARTED_AT = 1_700_000_000_000L;

    private final BinaryReplayStorageCodec codec = new BinaryReplayStorageCodec();
    private final Gson gson = new Gson();

    @TempDir
    java.nio.file.Path tempDir;

    @Test
    void loadsValidBinaryReplayArchive() throws Exception {
        byte[] archive = codec.finalizeReplay("valid", sampleTimeline(), "1.4.0", RECORDING_STARTED_AT);

        List<TimelineEvent> decoded = codec.decodeTimeline(archive, VersionUtil.MIN_RECORDING_VERSION);

        assertEquals(3, decoded.size());
        assertInstanceOf(TimelineEvent.PlayerMove.class, decoded.get(0));
        assertInstanceOf(TimelineEvent.BlockBreak.class, decoded.get(1));
        assertInstanceOf(TimelineEvent.PlayerQuit.class, decoded.get(2));
    }

    @Test
    void finalizeReplay_recordsExactPluginVersionButMaintainsCompatibilityFloor() throws Exception {
        byte[] archive = codec.finalizeReplay("versioned", sampleTimeline(), "1.5.0", RECORDING_STARTED_AT);
        Map<String, byte[]> entries = readArchiveEntries(archive);
        BinaryReplayManifest manifest = gson.fromJson(
                new String(entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME), StandardCharsets.UTF_8),
                BinaryReplayManifest.class);

        assertEquals("1.5.0", manifest.recordedWithVersion());
        assertEquals(VersionUtil.MIN_RECORDING_VERSION, manifest.minimumViewerVersion());
        assertEquals(BinaryReplayPayloadCompression.ZSTD.manifestValue(), manifest.payloadCompression());
        assertEquals(sampleTimeline(), codec.decodeTimeline(archive, VersionUtil.MIN_RECORDING_VERSION));
    }

    @Test
    void newBinaryReplayArchive_usesZstdPayloadCompression() throws Exception {
        byte[] archive = codec.finalizeReplay("zstd", sampleTimeline(), "1.5.0", RECORDING_STARTED_AT);
        Map<String, byte[]> entries = readArchiveEntries(archive);
        BinaryReplayManifest manifest = gson.fromJson(
                new String(entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME), StandardCharsets.UTF_8),
                BinaryReplayManifest.class);

        assertEquals(BinaryReplayPayloadCompression.ZSTD.manifestValue(), manifest.payloadCompression());
        assertEquals(sampleTimeline(), codec.decodeTimeline(archive, VersionUtil.MIN_RECORDING_VERSION));
    }

    @Test
    void lz4TimelineArchive_stillDecodes() throws Exception {
        byte[] archive = codec.finalizeReplay("lz4", sampleTimeline(), "1.5.0", RECORDING_STARTED_AT);
        Map<String, byte[]> entries = readArchiveEntries(archive);
        byte[] payload = decompress(entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME));
        entries.put(BinaryReplayFormat.REPLAY_ENTRY_NAME, BinaryReplayPayloadCompression.LZ4_FRAME.compress(payload));
        replaceManifestCompression(entries, BinaryReplayPayloadCompression.LZ4_FRAME.manifestValue());
        updateManifestChecksum(entries);

        assertEquals(sampleTimeline(), codec.decodeTimeline(writeArchive(entries), VersionUtil.MIN_RECORDING_VERSION));
    }

    @Test
    void missingPayloadCompression_fallsBackToMagicByteDetection() throws Exception {
        byte[] archive = codec.finalizeReplay("legacy-manifest-zstd", sampleTimeline(), "1.5.0", RECORDING_STARTED_AT);
        Map<String, byte[]> entries = readArchiveEntries(archive);
        replaceManifestCompression(entries, null);

        assertEquals(sampleTimeline(), codec.decodeTimeline(writeArchive(entries), VersionUtil.MIN_RECORDING_VERSION));
    }

    @Test
    void unsupportedPayloadCompression_failsClearly() throws Exception {
        byte[] archive = codec.finalizeReplay("bad-compression", sampleTimeline(), "1.5.0", RECORDING_STARTED_AT);
        Map<String, byte[]> entries = readArchiveEntries(archive);
        replaceManifestCompression(entries, "brotli");

        IOException ex = assertThrows(IOException.class,
                () -> codec.decodeTimeline(writeArchive(entries), VersionUtil.MIN_RECORDING_VERSION));

        assertTrue(ex.getMessage().contains("Unsupported binary replay payload compression: brotli"));
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
            manifest.payloadChecksumAlgorithm(),
            manifest.hasChunkData(),
            manifest.chunkRegionEntryCount(),
            manifest.chunkEntryCount(),
            manifest.chunkCoordinateHash(),
            manifest.chunkPayloadFormat(),
            manifest.chunkPayloadVersion())).getBytes(StandardCharsets.UTF_8));

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
        BinaryReplayStorageCodec.ParsedBinaryReplay replay = codec.openReplay(archive, VersionUtil.MIN_RECORDING_VERSION);

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

        BinaryReplayStorageCodec.ParsedBinaryReplay replay = codec.openReplay(writeArchive(entries), VersionUtil.MIN_RECORDING_VERSION);

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

        assertThrows(IOException.class, () -> codec.decodeTimeline(writeArchive(entries), VersionUtil.MIN_RECORDING_VERSION));
    }

    @Test
    void roundTripsSplitInventoryEvents() throws Exception {
    List<TimelineEvent> timeline = List.of(
        new TimelineEvent.EquipmentStateUpdate(
            1,
            "uuid-1",
            2,
            SerializedItemData.fromBytes(new byte[] {1, 2, 3}),
            SerializedItemData.fromBytes(new byte[] {4, 5, 6}),
            List.of(
                SerializedItemData.fromBytes(new byte[] {7}),
                SerializedItemData.fromBytes(new byte[] {8}),
                SerializedItemData.fromBytes(new byte[] {9}),
                SerializedItemData.empty()
            )),
        new TimelineEvent.InventoryStorageUpdate(
            1,
            "uuid-1",
            List.of(
                SerializedItemData.fromBytes(new byte[] {10}),
                SerializedItemData.empty(),
                SerializedItemData.fromBytes(new byte[] {11, 12})
            ))
    );

    byte[] archive = codec.finalizeReplay("inventory", timeline, "1.4.0", RECORDING_STARTED_AT);

    assertEquals(timeline, codec.decodeTimeline(archive, VersionUtil.MIN_RECORDING_VERSION));
    }

    @Test
    void finalizeReplay_withChunkArtifacts_includesChunkEntriesInArchive() throws Exception {
        try (BinaryChunkTempRegionFileWriter writer = new BinaryChunkTempRegionFileWriter(tempDir)) {
            writer.append(new CapturedChunkBaseline(new ChunkCoordinate("world", 0, 0), new byte[] { 7, 8, 9 }));
            ChunkRecordingArtifacts chunkArtifacts = writer.snapshotArtifacts();

            byte[] archive = codec.finalizeReplay(
                    "with-chunks",
                    new ReplaySaveRequest(sampleTimeline(), RECORDING_STARTED_AT, chunkArtifacts),
                    "1.4.0");

            Map<String, byte[]> entries = readArchiveEntries(archive);
            BinaryReplayManifest manifest = gson.fromJson(new String(entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME), StandardCharsets.UTF_8),
                    BinaryReplayManifest.class);

            assertTrue(manifest.hasChunkData());
            assertEquals(1, manifest.chunkRegionEntryCount());
            assertEquals(1, manifest.chunkEntryCount());
            assertTrue(entries.containsKey("chunks/world/r.0.0.brregion"));
            BinaryChunkRegionCodec.DecodedBinaryChunkRegion region = new BinaryChunkRegionCodec()
                    .decode(entries.get("chunks/world/r.0.0.brregion"));
            assertEquals(BinaryChunkCompression.ZSTD, region.indexEntries().getFirst().compression());
        }
    }

    @Test
    void inspectReplay_reportsChunkPayloadSizesSeparately() throws Exception {
        try (BinaryChunkTempRegionFileWriter writer = new BinaryChunkTempRegionFileWriter(tempDir)) {
            writer.append(new CapturedChunkBaseline(new ChunkCoordinate("world", 0, 0), new byte[] { 7, 8, 9 }));

            byte[] archive = codec.finalizeReplay(
                    "inspect-chunks",
                    new ReplaySaveRequest(sampleTimeline(), RECORDING_STARTED_AT, writer.snapshotArtifacts()),
                    "1.4.0");

            me.justindevb.replay.storage.ReplayInspection inspection = codec.inspectReplay("inspect-chunks", archive, VersionUtil.MIN_RECORDING_VERSION);

            assertEquals(1, inspection.chunkRegionEntryCount());
            assertEquals(1, inspection.chunkEntryCount());
            assertTrue(inspection.compressedChunkPayloadBytes() > 0);
            assertEquals(3, inspection.decompressedChunkPayloadBytes());
        }
    }

    @Test
    void inspectReplay_returnsManifestMetadataWhenViewerVersionIsTooOld() throws Exception {
        byte[] archive = codec.finalizeReplay("inspect-versioned", sampleTimeline(), "1.4.0", RECORDING_STARTED_AT);
        Map<String, byte[]> entries = readArchiveEntries(archive);
        BinaryReplayManifest manifest = gson.fromJson(
                new String(entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME), StandardCharsets.UTF_8),
                BinaryReplayManifest.class);

        entries.put(BinaryReplayFormat.MANIFEST_ENTRY_NAME, gson.toJson(new BinaryReplayManifest(
                manifest.formatVersion(),
                "1.5.0-alpha.9",
                "9.0.0",
                manifest.recordingStartedAtEpochMillis(),
                manifest.payloadChecksum(),
                manifest.payloadChecksumAlgorithm(),
                manifest.hasChunkData(),
                manifest.chunkRegionEntryCount(),
                manifest.chunkEntryCount(),
                manifest.chunkCoordinateHash(),
                manifest.chunkPayloadFormat(),
                manifest.chunkPayloadVersion())).getBytes(StandardCharsets.UTF_8));

        me.justindevb.replay.storage.ReplayInspection inspection = codec.inspectReplay("inspect-versioned", writeArchive(entries), "1.4.0");

        assertEquals("1.5.0-alpha.9", inspection.recordedWithVersion());
        assertEquals("9.0.0", inspection.minimumViewerVersion());
        assertEquals(0, inspection.recordCount());
        assertEquals(0, inspection.seekCheckpointCount());
        assertFalse(inspection.indexedPayload());
        assertTrue(inspection.storedBytes() > 0);
        assertTrue(inspection.compressedPayloadBytes() > 0);
    }

    @Test
    void finalizeReplay_withPacketFriendlyChunkArtifacts_tagsManifestWithBrcp() throws Exception {
        try (BinaryChunkTempRegionFileWriter writer = new BinaryChunkTempRegionFileWriter(tempDir)) {
            writer.append(new CapturedChunkBaseline(
                    new ChunkCoordinate("world", 0, 0),
                    new byte[] { 7, 8, 9 },
                    BinaryChunkPayloadFormat.BRCP));

            byte[] archive = codec.finalizeReplay(
                    "with-brcp-chunks",
                    new ReplaySaveRequest(sampleTimeline(), RECORDING_STARTED_AT, writer.snapshotArtifacts()),
                    "1.4.0");

            Map<String, byte[]> entries = readArchiveEntries(archive);
            BinaryReplayManifest manifest = gson.fromJson(
                    new String(entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME), StandardCharsets.UTF_8),
                    BinaryReplayManifest.class);

            assertEquals(BinaryChunkPayloadFormat.BRCP.manifestValue(), manifest.chunkPayloadFormat());
            assertEquals(1, manifest.chunkPayloadVersion());
            assertEquals(BinaryChunkPayloadFormat.BRCP, manifest.chunkMetadata().payloadFormat());
        }
    }

    @Test
    void decodeReplayData_loadsChunkEntriesWhenManifestAndArchiveMatch() throws Exception {
        try (BinaryChunkTempRegionFileWriter writer = new BinaryChunkTempRegionFileWriter(tempDir)) {
            writer.append(new CapturedChunkBaseline(new ChunkCoordinate("world", 0, 0), new byte[] { 7, 8, 9 }));

            byte[] archive = codec.finalizeReplay(
                    "decode-chunks",
                    new ReplaySaveRequest(sampleTimeline(), RECORDING_STARTED_AT, writer.snapshotArtifacts()),
                    "1.4.0");

            me.justindevb.replay.storage.ReplayPlaybackData replayData = codec.decodeReplayData(archive, VersionUtil.MIN_RECORDING_VERSION);

            assertEquals(3, replayData.timeline().size());
            assertTrue(replayData.chunkData().hasChunkData());
            assertEquals(1, replayData.chunkData().regionEntries().size());
            assertNotNull(replayData.chunkData().regionEntries().get("chunks/world/r.0.0.brregion"));
        }
    }

    @Test
    void decodeReplayData_softFailsWhenChunkManifestDoesNotMatchArchive() throws Exception {
        try (BinaryChunkTempRegionFileWriter writer = new BinaryChunkTempRegionFileWriter(tempDir)) {
            writer.append(new CapturedChunkBaseline(new ChunkCoordinate("world", 0, 0), new byte[] { 7, 8, 9 }));

            byte[] archive = codec.finalizeReplay(
                    "decode-soft-fail",
                    new ReplaySaveRequest(sampleTimeline(), RECORDING_STARTED_AT, writer.snapshotArtifacts()),
                    "1.4.0");
            Map<String, byte[]> entries = readArchiveEntries(archive);
            BinaryReplayManifest manifest = gson.fromJson(new String(entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME), StandardCharsets.UTF_8),
                    BinaryReplayManifest.class);
            BinaryReplayManifest mutated = new BinaryReplayManifest(
                    manifest.formatVersion(),
                    manifest.recordedWithVersion(),
                    manifest.minimumViewerVersion(),
                    manifest.recordingStartedAtEpochMillis(),
                    manifest.payloadChecksum(),
                    manifest.payloadChecksumAlgorithm(),
                    manifest.hasChunkData(),
                    manifest.chunkRegionEntryCount(),
                    manifest.chunkEntryCount() + 1,
                    manifest.chunkCoordinateHash(),
                    manifest.chunkPayloadFormat(),
                    manifest.chunkPayloadVersion());
            entries.put(BinaryReplayFormat.MANIFEST_ENTRY_NAME, gson.toJson(mutated).getBytes(StandardCharsets.UTF_8));

            me.justindevb.replay.storage.ReplayPlaybackData replayData = codec.decodeReplayData(writeArchive(entries), VersionUtil.MIN_RECORDING_VERSION);

            assertEquals(3, replayData.timeline().size());
            assertFalse(replayData.chunkData().hasChunkData());
            assertTrue(replayData.chunkData().regionEntries().isEmpty());
        }
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
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (BinaryReplayFormat.MANIFEST_ENTRY_NAME.equals(entry.getKey())
                        || BinaryReplayFormat.REPLAY_ENTRY_NAME.equals(entry.getKey())) {
                    continue;
                }
                writeStoredEntry(zip, entry.getKey(), entry.getValue());
            }
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
        return BinaryReplayPayloadCompression.detect(replayBytes).decompress(replayBytes);
    }

    private static byte[] compress(byte[] payload) throws IOException {
        return BinaryReplayPayloadCompression.DEFAULT.compress(payload);
    }

    private void replaceManifestCompression(Map<String, byte[]> entries, String compression) {
        JsonObject manifest = JsonParser.parseString(
                new String(entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME), StandardCharsets.UTF_8)).getAsJsonObject();
        if (compression == null) {
            manifest.remove("payloadCompression");
        } else {
            manifest.addProperty("payloadCompression", compression);
        }
        entries.put(BinaryReplayFormat.MANIFEST_ENTRY_NAME, gson.toJson(manifest).getBytes(StandardCharsets.UTF_8));
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
                manifest.payloadChecksumAlgorithm(),
                manifest.hasChunkData(),
                manifest.chunkRegionEntryCount(),
                manifest.chunkEntryCount(),
                manifest.chunkCoordinateHash(),
                manifest.chunkPayloadFormat(),
                manifest.chunkPayloadVersion(),
                manifest.payloadCompression());
        entries.put(BinaryReplayFormat.MANIFEST_ENTRY_NAME, gson.toJson(updated).getBytes(StandardCharsets.UTF_8));
    }
}
