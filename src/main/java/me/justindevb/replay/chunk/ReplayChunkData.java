package me.justindevb.replay.chunk;

import me.justindevb.replay.storage.binary.BinaryReplayChunkMetadata;
import me.justindevb.replay.storage.binary.BinaryReplayFormat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Finalized optional chunk archive entries stored alongside replay.bin.
 */
public record ReplayChunkData(
        BinaryReplayChunkMetadata metadata,
        Map<String, byte[]> regionEntries
) {

    public static final ReplayChunkData NONE = new ReplayChunkData(BinaryReplayChunkMetadata.none(), Map.of());

    public ReplayChunkData {
        metadata = Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(regionEntries, "regionEntries");

        Map<String, byte[]> normalizedEntries = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : regionEntries.entrySet()) {
            String entryName = Objects.requireNonNull(entry.getKey(), "entryName");
            byte[] bytes = Objects.requireNonNull(entry.getValue(), "entryBytes").clone();
            if (!entryName.startsWith(BinaryReplayFormat.RESERVED_CHUNKS_PREFIX)) {
                throw new IllegalArgumentException("chunk region entry must live under chunks/");
            }
            if (bytes.length == 0) {
                throw new IllegalArgumentException("chunk region entry bytes must not be empty");
            }
            normalizedEntries.put(entryName, bytes);
        }

        if (!metadata.hasChunkData() && !normalizedEntries.isEmpty()) {
            throw new IllegalArgumentException("chunk region entries require hasChunkData metadata");
        }
        if (metadata.hasChunkData() && metadata.chunkRegionEntryCount() != normalizedEntries.size()) {
            throw new IllegalArgumentException("chunkRegionEntryCount must match the number of region entries");
        }

        regionEntries = Map.copyOf(normalizedEntries);
    }

    public boolean hasChunkData() {
        return metadata.hasChunkData();
    }
}