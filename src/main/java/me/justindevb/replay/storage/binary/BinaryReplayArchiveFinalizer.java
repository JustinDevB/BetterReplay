package me.justindevb.replay.storage.binary;

import com.google.gson.Gson;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.ReplayFinalizer;
import net.jpountz.lz4.LZ4FrameOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Finalizes recovered append-log state into a .br archive.
 */
public final class BinaryReplayArchiveFinalizer implements ReplayFinalizer {

    private final Gson gson = new Gson();

    @Override
    public byte[] finalizeReplay(String replayName, List<TimelineEvent> timeline, String pluginVersion) throws IOException {
        return finalizeReplay(replayName, timeline, pluginVersion, System.currentTimeMillis());
    }

    public byte[] finalizeReplay(
            String replayName,
            List<TimelineEvent> timeline,
            String pluginVersion,
            long recordingStartedAtEpochMillis
    ) throws IOException {
        return finalizeRecoveredReplay(replayName, buildRecovery(timeline, recordingStartedAtEpochMillis), pluginVersion);
    }

    public byte[] finalizeRecoveredReplay(
            String replayName,
            BinaryReplayAppendLogRecovery recovery,
            String pluginVersion
    ) throws IOException {
        byte[] finalizedPayload = buildFinalizedPayload(recovery.records(), recovery.timeline(), recovery.stringTable());
        byte[] compressedPayload = compress(finalizedPayload);
        BinaryReplayManifest manifest = BinaryReplayManifest.createV1(
                pluginVersion,
                pluginVersion,
                resolveRecordingStartedAtEpochMillis(recovery),
                crc32cHex(compressedPayload));
        byte[] manifestBytes = gson.toJson(manifest).getBytes(StandardCharsets.UTF_8);
        return buildArchive(manifestBytes, compressedPayload);
    }

    private static BinaryReplayAppendLogRecovery buildRecovery(List<TimelineEvent> timeline, long recordingStartedAtEpochMillis) throws IOException {
        List<String> stringTable = new ArrayList<>();
        Map<String, Integer> stringIndexes = new LinkedHashMap<>();
        List<BinaryReplayAppendLogReader.DecodedRecord> records = new ArrayList<>();

        for (TimelineEvent event : timeline) {
            BinaryRecordType recordType = BinaryRecordType.forEvent(event);
            byte[] payload = BinaryReplayAppendLogCodec.encodeEvent(event, value -> indexString(value, stringTable, stringIndexes, records));
            byte[] recordContent = join(BinaryEncoding.encodeVarInt(recordType.tag()), payload);
            records.add(new BinaryReplayAppendLogReader.DecodedRecord(recordType, payload, 0, recordContent));
        }

        return new BinaryReplayAppendLogRecovery(new BinaryReplayAppendLogHeader(recordingStartedAtEpochMillis), records, timeline, stringTable,
                BinaryReplayRecoveryStopReason.CLEAN_EOF, 0);
    }

    private static long resolveRecordingStartedAtEpochMillis(BinaryReplayAppendLogRecovery recovery) {
        long recorded = recovery.header().recordingStartedAtEpochMillis();
        return recorded > 0 ? recorded : System.currentTimeMillis();
    }

    private static int indexString(
            String value,
            List<String> stringTable,
            Map<String, Integer> stringIndexes,
            List<BinaryReplayAppendLogReader.DecodedRecord> records
    ) {
        Integer existingIndex = stringIndexes.get(value);
        if (existingIndex != null) {
            return existingIndex;
        }

        int nextIndex = stringTable.size();
        stringTable.add(value);
        stringIndexes.put(value, nextIndex);
        byte[] payload = BinaryReplayAppendLogCodec.encodeDefineString(nextIndex, value);
        byte[] recordContent = join(BinaryEncoding.encodeVarInt(BinaryRecordType.DEFINE_STRING.tag()), payload);
        records.add(new BinaryReplayAppendLogReader.DecodedRecord(BinaryRecordType.DEFINE_STRING, payload, 0, recordContent));
        return nextIndex;
    }

    private static byte[] buildFinalizedPayload(
            List<BinaryReplayAppendLogReader.DecodedRecord> records,
            List<TimelineEvent> timeline,
            List<String> stringTable
    ) throws IOException {
        ByteArrayOutputStream eventStream = new ByteArrayOutputStream();
        List<BinaryTickIndexEntry> tickIndex = new ArrayList<>();
        long currentOffset = BinaryReplayFormat.PAYLOAD_HEADER_SIZE;
        long lastEventOffset = -1;
        int nextCheckpointTick = 0;
        int timelineIndex = 0;

        for (BinaryReplayAppendLogReader.DecodedRecord record : records) {
            if (record.type() != BinaryRecordType.DEFINE_STRING) {
                TimelineEvent event = timeline.get(timelineIndex++);
                if (lastEventOffset < 0) {
                    tickIndex.add(new BinaryTickIndexEntry(0, currentOffset));
                    nextCheckpointTick = BinaryReplayFormat.TICK_INDEX_INTERVAL;
                } else {
                    while (nextCheckpointTick <= event.tick()) {
                        tickIndex.add(new BinaryTickIndexEntry(nextCheckpointTick, lastEventOffset));
                        nextCheckpointTick += BinaryReplayFormat.TICK_INDEX_INTERVAL;
                    }
                }
                lastEventOffset = currentOffset;
            }

            byte[] recordBytes = join(BinaryEncoding.encodeVarInt(record.checksummedBytes().length), record.checksummedBytes());
            eventStream.writeBytes(recordBytes);
            currentOffset += recordBytes.length;
        }

        byte[] indexSection = buildIndexSection(stringTable, tickIndex);
        long indexSectionOffset = currentOffset;

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.writeBytes(BinaryReplayFormat.payloadMagicBytes());
        payload.write(BinaryReplayFormat.FORMAT_VERSION);
        payload.write(BinaryReplayFormat.PAYLOAD_FLAGS_NONE);
        payload.write(0x00);
        payload.write(0x00);
        payload.writeBytes(eventStream.toByteArray());
        payload.writeBytes(indexSection);
        payload.writeBytes(littleEndianLong(indexSectionOffset));
        return payload.toByteArray();
    }

    private static byte[] buildIndexSection(List<String> stringTable, List<BinaryTickIndexEntry> tickIndex) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(BinaryReplayFormat.indexSectionMagicBytes());
        out.writeBytes(BinaryEncoding.encodeVarInt(stringTable.size()));
        for (String value : stringTable) {
            out.writeBytes(BinaryEncoding.encodeLengthPrefixedString(value));
        }
        out.writeBytes(BinaryEncoding.encodeVarInt(tickIndex.size()));
        for (BinaryTickIndexEntry entry : tickIndex) {
            out.writeBytes(littleEndianInt(entry.tick()));
            out.writeBytes(littleEndianLong(entry.byteOffset()));
        }
        return out.toByteArray();
    }

    private static byte[] compress(byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(out)) {
            lz4.write(payload);
        }
        return out.toByteArray();
    }

    private static byte[] buildArchive(byte[] manifestBytes, byte[] replayBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            writeStoredEntry(zip, BinaryReplayFormat.MANIFEST_ENTRY_NAME, manifestBytes);
            writeStoredEntry(zip, BinaryReplayFormat.REPLAY_ENTRY_NAME, replayBytes);
        }
        return out.toByteArray();
    }

    private static void writeStoredEntry(ZipOutputStream zip, String entryName, byte[] contents) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
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

    private static String crc32cHex(byte[] payload) {
        CRC32C crc32c = new CRC32C();
        crc32c.update(payload, 0, payload.length);
        return "%08x".formatted(crc32c.getValue());
    }

    private static byte[] littleEndianInt(int value) {
        return ByteBuffer.allocate(Integer.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putInt(value)
                .array();
    }

    private static byte[] littleEndianLong(long value) {
        return ByteBuffer.allocate(Long.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putLong(value)
                .array();
    }

    private static byte[] join(byte[] first, byte[] second) {
        byte[] combined = new byte[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }
}