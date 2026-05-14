package me.justindevb.replay.storage.binary;

/**
 * Default corruption handling policy for optional chunk overlays.
 */
public enum BinaryChunkFailurePolicy {
    SOFT_FAIL_ENTITY_ONLY,
    HARD_FAIL;

    public static BinaryChunkFailurePolicy defaultPolicy() {
        return SOFT_FAIL_ENTITY_ONLY;
    }
}