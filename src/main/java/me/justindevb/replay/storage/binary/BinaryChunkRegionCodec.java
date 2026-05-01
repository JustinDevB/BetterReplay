package me.justindevb.replay.storage.binary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Encodes and decodes finalized per-region chunk entries.
 */
public final class BinaryChunkRegionCodec {

    private static final Comparator<BinaryChunkRegionEntry> ENTRY_ORDER = Comparator
            .comparingInt(BinaryChunkRegionEntry::localChunkX)
            .thenComparingInt(BinaryChunkRegionEntry::localChunkZ);

    public byte[] encode(List<BinaryChunkRegionEntry> entries) {
        Objects.requireNonNull(entries, "entries");

        List<BinaryChunkRegionEntry> sortedEntries = entries.stream()
                .map(Objects::requireNonNull)
                .sorted(ENTRY_ORDER)
                .toList();
        ensureDistinctChunks(sortedEntries);

        int indexEntryCount = sortedEntries.size();
        int payloadSectionOffset = BinaryReplayFormat.CHUNK_REGION_HEADER_SIZE
                + indexEntryCount * BinaryReplayFormat.CHUNK_REGION_INDEX_ENTRY_BYTES;

        ByteArrayOutputStream index = new ByteArrayOutputStream(indexEntryCount * BinaryReplayFormat.CHUNK_REGION_INDEX_ENTRY_BYTES);
        ByteArrayOutputStream payloads = new ByteArrayOutputStream();
        int payloadOffset = 0;
        for (BinaryChunkRegionEntry entry : sortedEntries) {
            byte[] compressedPayload = entry.compressedPayload();
            index.write(entry.localChunkX());
            index.write(entry.localChunkZ());
            index.write(entry.compression().codecId());
            index.write(0x00);
            index.writeBytes(littleEndianInt(payloadOffset));
            index.writeBytes(littleEndianInt(compressedPayload.length));
            index.writeBytes(littleEndianInt(entry.uncompressedLength()));
            payloads.writeBytes(compressedPayload);
            payloadOffset += compressedPayload.length;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(BinaryReplayFormat.chunkRegionMagicBytes());
        out.write(BinaryReplayFormat.CHUNK_REGION_VERSION);
        out.write(BinaryReplayFormat.CHUNK_REGION_FLAGS_NONE);
        out.write(0x00);
        out.write(0x00);
        out.writeBytes(littleEndianInt(indexEntryCount));
        out.writeBytes(littleEndianInt(payloadSectionOffset));
        out.writeBytes(index.toByteArray());
        out.writeBytes(payloads.toByteArray());
        return out.toByteArray();
    }

    public DecodedBinaryChunkRegion decode(byte[] regionBytes) throws IOException {
        Objects.requireNonNull(regionBytes, "regionBytes");
        if (regionBytes.length < BinaryReplayFormat.CHUNK_REGION_HEADER_SIZE) {
            throw new IOException("Chunk region entry is too short");
        }
        if (!Arrays.equals(Arrays.copyOfRange(regionBytes, 0, BinaryReplayFormat.CHUNK_REGION_MAGIC.length), BinaryReplayFormat.CHUNK_REGION_MAGIC)) {
            throw new IOException("Invalid chunk region entry magic");
        }
        if ((regionBytes[4] & 0xFF) != BinaryReplayFormat.CHUNK_REGION_VERSION) {
            throw new IOException("Unsupported chunk region entry version: " + (regionBytes[4] & 0xFF));
        }
        if ((regionBytes[5] & 0xFF) != BinaryReplayFormat.CHUNK_REGION_FLAGS_NONE) {
            throw new IOException("Unsupported chunk region entry flags: " + (regionBytes[5] & 0xFF));
        }
        if (regionBytes[6] != 0 || regionBytes[7] != 0) {
            throw new IOException("Chunk region entry reserved header bytes must be zero");
        }

        ByteBuffer header = ByteBuffer.wrap(regionBytes).order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER);
        int indexEntryCount = header.getInt(8);
        int payloadSectionOffset = header.getInt(12);
        if (indexEntryCount < 0) {
            throw new IOException("Chunk region entry count must not be negative");
        }

        int expectedPayloadOffset = BinaryReplayFormat.CHUNK_REGION_HEADER_SIZE
                + indexEntryCount * BinaryReplayFormat.CHUNK_REGION_INDEX_ENTRY_BYTES;
        if (payloadSectionOffset != expectedPayloadOffset) {
            throw new IOException("Chunk region payload section offset does not match header/index size");
        }
        if (payloadSectionOffset > regionBytes.length) {
            throw new IOException("Chunk region payload section exceeds entry length");
        }

        List<BinaryChunkRegionIndexEntry> indexEntries = new ArrayList<>(indexEntryCount);
        List<BinaryChunkRegionEntry> entries = new ArrayList<>(indexEntryCount);
        Set<Integer> seenChunks = new HashSet<>();
        List<PayloadRange> ranges = new ArrayList<>(indexEntryCount);
        int offset = BinaryReplayFormat.CHUNK_REGION_HEADER_SIZE;
        for (int index = 0; index < indexEntryCount; index++) {
            int localChunkX = regionBytes[offset] & 0xFF;
            int localChunkZ = regionBytes[offset + 1] & 0xFF;
            BinaryChunkCompression compression = BinaryChunkCompression.fromCodecId(regionBytes[offset + 2] & 0xFF);
            if (regionBytes[offset + 3] != 0) {
                throw new IOException("Chunk region index reserved byte must be zero");
            }
            ByteBuffer row = ByteBuffer.wrap(regionBytes, offset + 4, BinaryReplayFormat.CHUNK_REGION_INDEX_ENTRY_BYTES - 4)
                    .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER);
            int payloadOffset = row.getInt();
            int compressedLength = row.getInt();
            int uncompressedLength = row.getInt();
            BinaryChunkRegionIndexEntry indexEntry = new BinaryChunkRegionIndexEntry(
                    localChunkX,
                    localChunkZ,
                    payloadOffset,
                    compressedLength,
                    uncompressedLength,
                    compression);
            int chunkKey = (localChunkX << 8) | localChunkZ;
            if (!seenChunks.add(chunkKey)) {
                throw new IOException("Chunk region index contains duplicate chunk coordinates");
            }
            int payloadStart = payloadSectionOffset + payloadOffset;
            int payloadEnd = payloadStart + compressedLength;
            if (payloadStart < payloadSectionOffset || payloadEnd < payloadStart || payloadEnd > regionBytes.length) {
                throw new IOException("Chunk region payload bounds exceed entry length");
            }
            ranges.add(new PayloadRange(payloadStart, payloadEnd));
            indexEntries.add(indexEntry);
            entries.add(new BinaryChunkRegionEntry(
                    localChunkX,
                    localChunkZ,
                    uncompressedLength,
                    compression,
                    Arrays.copyOfRange(regionBytes, payloadStart, payloadEnd)));
            offset += BinaryReplayFormat.CHUNK_REGION_INDEX_ENTRY_BYTES;
        }

        ranges.sort(Comparator.comparingInt(PayloadRange::start));
        int lastEnd = payloadSectionOffset;
        for (PayloadRange range : ranges) {
            if (range.start < lastEnd) {
                throw new IOException("Chunk region payload ranges overlap");
            }
            lastEnd = range.end;
        }

        return new DecodedBinaryChunkRegion(List.copyOf(indexEntries), List.copyOf(entries));
    }

    private static void ensureDistinctChunks(List<BinaryChunkRegionEntry> entries) {
        BinaryChunkRegionEntry previous = null;
        for (BinaryChunkRegionEntry entry : entries) {
            if (entry.sameChunk(previous)) {
                throw new IllegalArgumentException("Chunk region entries must not contain duplicate chunk coordinates");
            }
            previous = entry;
        }
    }

    private static byte[] littleEndianInt(int value) {
        return ByteBuffer.allocate(Integer.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putInt(value)
                .array();
    }

    public record DecodedBinaryChunkRegion(
            List<BinaryChunkRegionIndexEntry> indexEntries,
            List<BinaryChunkRegionEntry> entries
    ) {
    }

    private record PayloadRange(int start, int end) {
    }
}