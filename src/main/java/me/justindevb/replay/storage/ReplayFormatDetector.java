package me.justindevb.replay.storage;

/**
 * Resolves which storage codec should read a persisted replay payload.
 */
public interface ReplayFormatDetector {

    ReplayStorageCodec detectCodec(String replayName, byte[] storedBytes);
}