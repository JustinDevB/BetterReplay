package me.justindevb.replay.storage.binary;

import com.google.gson.Gson;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.ReplayFormat;
import me.justindevb.replay.storage.ReplayInspection;
import me.justindevb.replay.storage.ReplayInspectionBuilder;
import me.justindevb.replay.storage.ReplayIndexedTimeline;
import me.justindevb.replay.storage.ReplayStorageCodec;
import me.justindevb.replay.util.VersionUtil;
import net.jpountz.lz4.LZ4FrameInputStream;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32C;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Loads finalized BetterReplay .br archives for playback.
 */
public final class BinaryReplayStorageCodec implements ReplayStorageCodec {

    private final Gson gson;
    private final BinaryReplayArchiveFinalizer finalizer;

    public BinaryReplayStorageCodec() {
        this(new Gson(), new BinaryReplayArchiveFinalizer());
    }

    BinaryReplayStorageCodec(Gson gson, BinaryReplayArchiveFinalizer finalizer) {
        this.gson = Objects.requireNonNull(gson, "gson");
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
    }

    @Override
    public ReplayFormat format() {
        return ReplayFormat.BINARY_ARCHIVE;
    }

    @Override
    public boolean canDecode(String replayName, byte[] storedBytes) {
        try {
            readArchiveEntries(storedBytes);
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    @Override
    public String fileExtension(boolean compressionEnabled) {
        return BinaryReplayFormat.FILE_EXTENSION;
    }

    @Override
    public boolean supportsCompression() {
        return false;
    }

    @Override
    public byte[] encodeTimeline(List<TimelineEvent> timeline, String pluginVersion) throws IOException {
        return finalizer.finalizeReplay("replay", timeline, pluginVersion);
    }

    @Override
    public byte[] finalizeReplay(String replayName, List<TimelineEvent> timeline, String pluginVersion) throws IOException {
        return finalizer.finalizeReplay(replayName, timeline, pluginVersion);
    }

    @Override
    public byte[] finalizeReplay(
            String replayName,
            List<TimelineEvent> timeline,
            String pluginVersion,
            Long recordingStartedAtEpochMillis
    ) throws IOException {
        if (recordingStartedAtEpochMillis == null) {
            return finalizer.finalizeReplay(replayName, timeline, pluginVersion);
        }
        return finalizer.finalizeReplay(replayName, timeline, pluginVersion, recordingStartedAtEpochMillis);
    }

    @Override
    public List<TimelineEvent> decodeTimeline(byte[] storedBytes, String runningVersion) throws IOException {
        return openReplay(storedBytes, runningVersion).timeline();
    }

    @Override
    public ReplayInspection inspectReplay(String replayName, byte[] storedBytes, String runningVersion) throws IOException {
        ArchiveEntries archiveEntries = readArchiveEntries(storedBytes);
        BinaryReplayManifest manifest = parseManifest(archiveEntries.manifestBytes());
        validateManifest(manifest, archiveEntries.replayBytes(), runningVersion);

        byte[] payload = decompress(archiveEntries.replayBytes());
        validatePayloadHeader(payload);
        ParsedPayload parsedPayload = parsePayload(payload);
        LazyTimeline timeline = new LazyTimeline(payload, parsedPayload.events(), parsedPayload.stringTable(), parsedPayload.tickIndex());

        return ReplayInspectionBuilder.build(
                replayName,
                format(),
                storedBytes.length,
                archiveEntries.replayBytes().length,
                payload.length,
                manifest.recordingStartedAtEpochMillis(),
                manifest.recordedWithVersion(),
                manifest.minimumViewerVersion(),
                parsedPayload.indexLoaded(),
                parsedPayload.tickIndex().size(),
                timeline);
    }

    @Override
    public File writeReplayFile(String replayName, byte[] storedBytes, String runningVersion) throws IOException {
        File tempFile = File.createTempFile("replay_" + replayName + "_", BinaryReplayFormat.FILE_EXTENSION);
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(), storedBytes);
        return tempFile;
    }

    ParsedBinaryReplay openReplay(byte[] storedBytes, String runningVersion) throws IOException {
        ArchiveEntries archiveEntries = readArchiveEntries(storedBytes);
        BinaryReplayManifest manifest = parseManifest(archiveEntries.manifestBytes());
        validateManifest(manifest, archiveEntries.replayBytes(), runningVersion);

        byte[] payload = decompress(archiveEntries.replayBytes());
        validatePayloadHeader(payload);

        ParsedPayload parsedPayload = parsePayload(payload);
        LazyTimeline timeline = new LazyTimeline(payload, parsedPayload.events(), parsedPayload.stringTable(), parsedPayload.tickIndex());
        return new ParsedBinaryReplay(manifest, timeline, parsedPayload.tickIndex(), parsedPayload.stringTable(), parsedPayload.indexLoaded());
    }

    private ArchiveEntries readArchiveEntries(byte[] storedBytes) throws IOException {
        byte[] manifestBytes = null;
        byte[] replayBytes = null;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(storedBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                byte[] bytes = zip.readAllBytes();
                if (BinaryReplayFormat.MANIFEST_ENTRY_NAME.equals(entry.getName())) {
                    manifestBytes = bytes;
                } else if (BinaryReplayFormat.REPLAY_ENTRY_NAME.equals(entry.getName())) {
                    replayBytes = bytes;
                }
                zip.closeEntry();
            }
        }

        if (manifestBytes == null || replayBytes == null) {
            throw new IOException("Binary replay archive is missing required entries");
        }
        return new ArchiveEntries(manifestBytes, replayBytes);
    }

    private BinaryReplayManifest parseManifest(byte[] manifestBytes) throws IOException {
        try {
            return gson.fromJson(new String(manifestBytes, BinaryReplayFormat.STRING_CHARSET), BinaryReplayManifest.class);
        } catch (RuntimeException ex) {
            throw new IOException("Failed to parse binary replay manifest", ex);
        }
    }

    private void validateManifest(BinaryReplayManifest manifest, byte[] replayBytes, String runningVersion) throws IOException {
        if (manifest.formatVersion() != BinaryReplayFormat.FORMAT_VERSION) {
            throw new IOException("Unsupported binary replay format version: " + manifest.formatVersion());
        }
        if (!BinaryReplayFormat.PAYLOAD_CHECKSUM_ALGORITHM.equals(manifest.payloadChecksumAlgorithm())) {
            throw new IOException("Unsupported payload checksum algorithm: " + manifest.payloadChecksumAlgorithm());
        }
        if (!VersionUtil.isAtLeast(runningVersion, manifest.minimumViewerVersion())) {
            throw new VersionUtil.ReplayVersionMismatchException(manifest.minimumViewerVersion(), runningVersion);
        }
        String actualChecksum = crc32cHex(replayBytes);
        if (!actualChecksum.equals(manifest.payloadChecksum())) {
            throw new IOException("Binary replay payload checksum mismatch");
        }
    }

    private static byte[] decompress(byte[] replayBytes) throws IOException {
        try (LZ4FrameInputStream lz4 = new LZ4FrameInputStream(new ByteArrayInputStream(replayBytes))) {
            return lz4.readAllBytes();
        }
    }

    private static void validatePayloadHeader(byte[] payload) throws IOException {
        if (payload.length < BinaryReplayFormat.PAYLOAD_HEADER_SIZE) {
            throw new IOException("Binary replay payload is too short");
        }
        if (!Arrays.equals(Arrays.copyOfRange(payload, 0, BinaryReplayFormat.PAYLOAD_MAGIC.length), BinaryReplayFormat.PAYLOAD_MAGIC)) {
            throw new IOException("Invalid binary replay payload magic");
        }
        if ((payload[4] & 0xFF) != BinaryReplayFormat.FORMAT_VERSION) {
            throw new IOException("Unsupported binary replay payload version: " + (payload[4] & 0xFF));
        }
        if ((payload[5] & 0xFF) != BinaryReplayFormat.PAYLOAD_FLAGS_NONE) {
            throw new IOException("Unsupported binary replay payload flags: " + (payload[5] & 0xFF));
        }
        if (payload[6] != 0 || payload[7] != 0) {
            throw new IOException("Binary replay payload reserved header bytes must be zero");
        }
    }

    private static ParsedPayload parsePayload(byte[] payload) throws IOException {
        IndexSection indexSection = tryParseIndexSection(payload);
        int eventSectionEnd = indexSection != null ? indexSection.indexSectionOffset() : payload.length;
        ScannedEventStream scanned = scanEventStream(payload, BinaryReplayFormat.PAYLOAD_HEADER_SIZE, eventSectionEnd);

        List<String> stringTable = indexSection != null ? indexSection.stringTable() : scanned.stringTable();
        List<BinaryTickIndexEntry> tickIndex = indexSection != null ? indexSection.tickIndex() : rebuildTickIndex(scanned.events());

        if (indexSection != null && !scanned.stringTable().equals(stringTable)) {
            throw new IOException("Binary replay string table does not match finalized index section");
        }

        return new ParsedPayload(scanned.events(), stringTable, tickIndex, indexSection != null);
    }

    private static IndexSection tryParseIndexSection(byte[] payload) throws IOException {
        if (payload.length < BinaryReplayFormat.PAYLOAD_HEADER_SIZE + BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES) {
            return null;
        }

        int footerOffset = payload.length - BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES;
        long indexSectionOffsetLong = ByteBuffer.wrap(payload, footerOffset, BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .getLong();
        if (indexSectionOffsetLong < BinaryReplayFormat.PAYLOAD_HEADER_SIZE || indexSectionOffsetLong >= footerOffset) {
            return null;
        }

        int indexSectionOffset = Math.toIntExact(indexSectionOffsetLong);
        if (indexSectionOffset + BinaryReplayFormat.INDEX_SECTION_MAGIC.length > footerOffset) {
            return null;
        }
        if (!Arrays.equals(
                Arrays.copyOfRange(payload, indexSectionOffset, indexSectionOffset + BinaryReplayFormat.INDEX_SECTION_MAGIC.length),
                BinaryReplayFormat.indexSectionMagicBytes())) {
            return null;
        }

        byte[] indexSectionBytes = Arrays.copyOfRange(payload, indexSectionOffset, footerOffset);
        BinaryReplayAppendLogCodec.Cursor cursor = new BinaryReplayAppendLogCodec.Cursor(indexSectionBytes);
        cursor.readBytes(BinaryReplayFormat.INDEX_SECTION_MAGIC.length);

        try {
            int stringCount = cursor.readVarInt();
            List<String> stringTable = new ArrayList<>(stringCount);
            for (int index = 0; index < stringCount; index++) {
                stringTable.add(cursor.readLengthPrefixedString().value());
            }

            int tickIndexCount = cursor.readVarInt();
            List<BinaryTickIndexEntry> tickIndex = new ArrayList<>(tickIndexCount);
            for (int index = 0; index < tickIndexCount; index++) {
                tickIndex.add(new BinaryTickIndexEntry(cursor.readInt(), cursor.readLong()));
            }
            cursor.ensureFullyRead();
            return new IndexSection(indexSectionOffset, stringTable, tickIndex);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Failed to parse binary replay index section", ex);
        }
    }

    private static ScannedEventStream scanEventStream(byte[] payload, int startOffset, int endOffset) throws IOException {
        int offset = startOffset;
        List<EventSlice> events = new ArrayList<>();
        List<String> stringTable = new ArrayList<>();

        while (offset < endOffset) {
            VarIntRead recordLengthRead = readVarInt(payload, offset, endOffset);
            int recordContentOffset = recordLengthRead.nextOffset();
            int recordContentEnd = recordContentOffset + recordLengthRead.value();
            if (recordLengthRead.value() < 0 || recordContentEnd > endOffset) {
                throw new IOException("Malformed finalized replay record length");
            }

            VarIntRead recordTypeRead = readVarInt(payload, recordContentOffset, recordContentEnd);
            BinaryRecordType recordType = BinaryRecordType.fromTag(recordTypeRead.value())
                    .orElseThrow(() -> new IOException("Unknown binary replay record tag: " + recordTypeRead.value()));

            int eventPayloadOffset = recordTypeRead.nextOffset();
            int eventPayloadLength = recordContentEnd - eventPayloadOffset;
            byte[] eventPayload = Arrays.copyOfRange(payload, eventPayloadOffset, recordContentEnd);

            if (recordType == BinaryRecordType.DEFINE_STRING) {
                BinaryReplayAppendLogCodec.DefinedString definedString = BinaryReplayAppendLogCodec.decodeDefineString(eventPayload);
                if (definedString.index() != stringTable.size()) {
                    throw new IOException("Invalid string-table index in finalized replay: " + definedString.index());
                }
                stringTable.add(definedString.value());
            } else {
                if (eventPayloadLength < Integer.BYTES) {
                    throw new IOException("Finalized replay event payload is too short to contain a tick");
                }
                int tick = readLittleEndianInt(payload, eventPayloadOffset);
                events.add(new EventSlice(recordType, offset, eventPayloadOffset, eventPayloadLength, tick));
            }

            offset = recordContentEnd;
        }

        return new ScannedEventStream(events, stringTable);
    }

    private static List<BinaryTickIndexEntry> rebuildTickIndex(List<EventSlice> events) {
        if (events.isEmpty()) {
            return List.of();
        }

        List<BinaryTickIndexEntry> tickIndex = new ArrayList<>();
        long lastEventOffset = -1;
        int nextCheckpointTick = 0;

        for (EventSlice event : events) {
            if (lastEventOffset < 0) {
                tickIndex.add(new BinaryTickIndexEntry(0, event.recordOffset()));
                nextCheckpointTick = BinaryReplayFormat.TICK_INDEX_INTERVAL;
            } else {
                while (nextCheckpointTick <= event.tick()) {
                    tickIndex.add(new BinaryTickIndexEntry(nextCheckpointTick, lastEventOffset));
                    nextCheckpointTick += BinaryReplayFormat.TICK_INDEX_INTERVAL;
                }
            }
            lastEventOffset = event.recordOffset();
        }

        return tickIndex;
    }

    private static VarIntRead readVarInt(byte[] bytes, int startOffset, int endOffset) throws IOException {
        int value = 0;
        int shift = 0;
        int offset = startOffset;
        while (offset < endOffset) {
            int current = bytes[offset++] & 0xFF;
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return new VarIntRead(value, offset);
            }
            shift += 7;
            if (shift > 28) {
                throw new IOException("VarInt is too large in binary replay payload");
            }
        }
        throw new IOException("Unexpected end of payload while reading VarInt");
    }

    private static int readLittleEndianInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, Integer.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .getInt();
    }

    private static String crc32cHex(byte[] bytes) {
        CRC32C crc32c = new CRC32C();
        crc32c.update(bytes, 0, bytes.length);
        return "%08x".formatted(crc32c.getValue());
    }

    record ParsedBinaryReplay(
            BinaryReplayManifest manifest,
            LazyTimeline timeline,
            List<BinaryTickIndexEntry> tickIndex,
            List<String> stringTable,
            boolean indexLoaded
    ) {
    }

    static final class LazyTimeline extends AbstractList<TimelineEvent> implements ReplayIndexedTimeline {

        private final byte[] payload;
        private final List<EventSlice> events;
        private final List<String> stringTable;
        private final List<BinaryTickIndexEntry> tickIndex;
        private final Map<Long, Integer> eventIndexByOffset;

        LazyTimeline(byte[] payload, List<EventSlice> events, List<String> stringTable, List<BinaryTickIndexEntry> tickIndex) {
            this.payload = payload;
            this.events = List.copyOf(events);
            this.stringTable = List.copyOf(stringTable);
            this.tickIndex = List.copyOf(tickIndex);
            Map<Long, Integer> offsetIndex = new HashMap<>();
            for (int index = 0; index < events.size(); index++) {
                offsetIndex.put(events.get(index).recordOffset(), index);
            }
            this.eventIndexByOffset = Map.copyOf(offsetIndex);
        }

        @Override
        public TimelineEvent get(int index) {
            EventSlice slice = events.get(index);
            byte[] eventPayload = Arrays.copyOfRange(payload, slice.payloadOffset(), slice.payloadOffset() + slice.payloadLength());
            return BinaryReplayAppendLogCodec.decodeEvent(slice.recordType(), eventPayload, stringTable);
        }

        @Override
        public int size() {
            return events.size();
        }

        @Override
        public int findEventIndexAtOrAfterTick(int targetTick) {
            int candidateIndex = 0;
            if (!tickIndex.isEmpty()) {
                BinaryTickIndexEntry checkpoint = tickIndex.get(findCheckpointIndex(targetTick));
                candidateIndex = eventIndexByOffset.getOrDefault(checkpoint.byteOffset(), 0);
            }
            while (candidateIndex < events.size() && events.get(candidateIndex).tick() < targetTick) {
                candidateIndex++;
            }
            return candidateIndex;
        }

        private int findCheckpointIndex(int targetTick) {
            int low = 0;
            int high = tickIndex.size() - 1;
            int best = 0;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                int checkpointTick = tickIndex.get(mid).tick();
                if (checkpointTick <= targetTick) {
                    best = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            return best;
        }
    }

    private record ArchiveEntries(byte[] manifestBytes, byte[] replayBytes) {
    }

    private record ParsedPayload(List<EventSlice> events, List<String> stringTable, List<BinaryTickIndexEntry> tickIndex, boolean indexLoaded) {
    }

    private record IndexSection(int indexSectionOffset, List<String> stringTable, List<BinaryTickIndexEntry> tickIndex) {
    }

    private record ScannedEventStream(List<EventSlice> events, List<String> stringTable) {
    }

    private record EventSlice(BinaryRecordType recordType, long recordOffset, int payloadOffset, int payloadLength, int tick) {
    }

    private record VarIntRead(int value, int nextOffset) {
    }
}