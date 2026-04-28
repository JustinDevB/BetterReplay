package me.justindevb.replay.storage.binary;

/**
 * A finalized replay tick-index entry consisting of an explicit checkpoint tick and byte offset.
 */
public record BinaryTickIndexEntry(int tick, long byteOffset) {

    public BinaryTickIndexEntry {
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be non-negative");
        }
        if (byteOffset < 0) {
            throw new IllegalArgumentException("byteOffset must be non-negative");
        }
        if (tick % BinaryReplayFormat.TICK_INDEX_INTERVAL != 0) {
            throw new IllegalArgumentException(
                    "tick must align to the fixed checkpoint interval of " + BinaryReplayFormat.TICK_INDEX_INTERVAL);
        }
    }
}