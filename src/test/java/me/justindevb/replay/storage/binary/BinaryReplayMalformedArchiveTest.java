package me.justindevb.replay.storage.binary;

import com.google.gson.Gson;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryReplayMalformedArchiveTest {

    private static final Gson GSON = new Gson();
    private final BinaryReplayStorageCodec codec = new BinaryReplayStorageCodec();

    @Test
    void failsWhenManifestEntryIsMissing() throws Exception {
        Map<String, byte[]> entries = readArchiveEntries(readFixture("goldens/minimal-v1.br"));
        entries.remove(BinaryReplayFormat.MANIFEST_ENTRY_NAME);

        IOException ex = assertThrows(IOException.class, () -> codec.decodeTimeline(writeArchive(entries), "1.4.0"));

        assertTrue(ex.getMessage().contains("missing required entries"));
    }

    @Test
    void failsWhenReplayEntryIsMissing() throws Exception {
        Map<String, byte[]> entries = readArchiveEntries(readFixture("goldens/minimal-v1.br"));
        entries.remove(BinaryReplayFormat.REPLAY_ENTRY_NAME);

        IOException ex = assertThrows(IOException.class, () -> codec.decodeTimeline(writeArchive(entries), "1.4.0"));

        assertTrue(ex.getMessage().contains("missing required entries"));
    }

    @Test
    void failsWhenManifestChecksumDoesNotMatchReplayBytes() throws Exception {
        Map<String, byte[]> entries = readArchiveEntries(readFixture("goldens/minimal-v1.br"));
        byte[] replayBytes = Arrays.copyOf(entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME), entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME).length);
        replayBytes[replayBytes.length - 1] ^= 0x01;
        entries.put(BinaryReplayFormat.REPLAY_ENTRY_NAME, replayBytes);

        IOException ex = assertThrows(IOException.class, () -> codec.decodeTimeline(writeArchive(entries), "1.4.0"));

        assertTrue(ex.getMessage().contains("checksum mismatch"));
    }

    @Test
    void failsWhenIndexSectionIsPresentButTruncated() throws Exception {
        Map<String, byte[]> entries = readArchiveEntries(readFixture("goldens/minimal-v1.br"));
        byte[] payload = decompress(entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME));
        int footerOffset = payload.length - BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES;
        long indexOffset = ByteBuffer.wrap(payload, footerOffset, BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .getLong();

        byte[] truncatedPayload = Arrays.copyOf(payload, footerOffset - 1);
        ByteBuffer.wrap(truncatedPayload,
                        truncatedPayload.length - BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES,
                        BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putLong(indexOffset);

        entries.put(BinaryReplayFormat.REPLAY_ENTRY_NAME, compress(truncatedPayload));
        updateManifestChecksum(entries);

        IOException ex = assertThrows(IOException.class, () -> codec.decodeTimeline(writeArchive(entries), "1.4.0"));

        assertTrue(ex.getMessage().contains("index section"));
    }

    private static byte[] readFixture(String resourcePath) throws IOException {
        try (InputStream in = BinaryReplayMalformedArchiveTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Missing fixture resource: " + resourcePath);
            }
            return in.readAllBytes();
        }
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
            for (String name : Arrays.asList(BinaryReplayFormat.MANIFEST_ENTRY_NAME, BinaryReplayFormat.REPLAY_ENTRY_NAME)) {
                if (entries.containsKey(name)) {
                    writeStoredEntry(zip, name, entries.get(name));
                }
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

    private static void updateManifestChecksum(Map<String, byte[]> entries) {
        BinaryReplayManifest manifest = GSON.fromJson(
                new String(entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME), StandardCharsets.UTF_8),
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
        entries.put(BinaryReplayFormat.MANIFEST_ENTRY_NAME, GSON.toJson(updated).getBytes(StandardCharsets.UTF_8));
    }
}