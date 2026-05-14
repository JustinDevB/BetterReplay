package me.justindevb.replay.chunk;

import me.justindevb.replay.storage.binary.BinaryReplayChunkMetadata;

import java.io.IOException;
import java.util.Map;

/**
 * Reads finalized chunk archive entries from a stored replay container.
 */
public interface ReplayChunkArchiveReader {

    ReplayChunkData read(BinaryReplayChunkMetadata metadata, Map<String, byte[]> archiveEntries) throws IOException;
}