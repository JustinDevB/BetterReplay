package me.justindevb.replay.storage.binary;

import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.ReplayAppendLogWriter;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32C;

/**
 * Writes append-only replay temp files with self-framed records and per-record CRC32C.
 */
public final class BinaryReplayAppendLogWriter implements ReplayAppendLogWriter {

    private final Path path;
    private final OutputStream outputStream;
    private final Map<String, Integer> stringIndices = new HashMap<>();
    private final BinaryReplayAppendLogHeader header;

    public BinaryReplayAppendLogWriter(Path path) throws IOException {
        this(path, new BinaryReplayAppendLogHeader(System.currentTimeMillis()));
    }

    public BinaryReplayAppendLogWriter(Path path, long recordingStartedAtEpochMillis) throws IOException {
        this(path, new BinaryReplayAppendLogHeader(recordingStartedAtEpochMillis));
    }

    public BinaryReplayAppendLogWriter(Path path, BinaryReplayAppendLogHeader header) throws IOException {
        this.path = path;
        this.header = header;
        Files.createDirectories(path.getParent());
        this.outputStream = new BufferedOutputStream(Files.newOutputStream(path));
        this.outputStream.write(encodeHeader(header));
        this.outputStream.flush();
    }

    public Path path() {
        return path;
    }

    public BinaryReplayAppendLogHeader header() {
        return header;
    }

    @Override
    public synchronized void append(TimelineEvent event) throws IOException {
        byte[] payload = BinaryReplayAppendLogCodec.encodeEvent(event, this::ensureStringDefined);
        writeRecord(BinaryRecordType.forEvent(event), payload);
    }

    @Override
    public synchronized void flush() throws IOException {
        outputStream.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        outputStream.flush();
        outputStream.close();
    }

    private int ensureStringDefined(String value) throws IOException {
        Integer existing = stringIndices.get(value);
        if (existing != null) {
            return existing;
        }

        int index = stringIndices.size();
        stringIndices.put(value, index);
        writeRecord(BinaryRecordType.DEFINE_STRING, BinaryReplayAppendLogCodec.encodeDefineString(index, value));
        return index;
    }

    private void writeRecord(BinaryRecordType recordType, byte[] payload) throws IOException {
        ByteArrayOutputStream recordBytes = new ByteArrayOutputStream();
        recordBytes.writeBytes(BinaryEncoding.encodeVarInt(recordType.tag()));
        recordBytes.writeBytes(payload);

        byte[] recordContent = recordBytes.toByteArray();
        outputStream.write(BinaryEncoding.encodeVarInt(recordContent.length));
        outputStream.write(recordContent);
        outputStream.write(intToBytes(calculateCrc32c(recordContent)));
    }

    private static int calculateCrc32c(byte[] recordContent) {
        CRC32C crc32c = new CRC32C();
        crc32c.update(recordContent, 0, recordContent.length);
        return (int) crc32c.getValue();
    }

    private static byte[] intToBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putInt(value)
                .array();
    }

    private static byte[] encodeHeader(BinaryReplayAppendLogHeader header) {
        return ByteBuffer.allocate(BinaryReplayFormat.APPEND_LOG_HEADER_SIZE)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .put(BinaryReplayFormat.appendLogMagicBytes())
                .put((byte) BinaryReplayFormat.APPEND_LOG_HEADER_VERSION)
                .put((byte) BinaryReplayFormat.APPEND_LOG_HEADER_FLAGS_NONE)
                .putShort((short) 0)
                .putLong(header.recordingStartedAtEpochMillis())
                .array();
    }
}