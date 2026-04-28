package me.justindevb.replay.storage;

import java.util.List;
import java.util.Objects;

/**
 * Default detector that chooses the first codec that recognizes a persisted payload.
 */
public final class DefaultReplayFormatDetector implements ReplayFormatDetector {

    private final List<ReplayStorageCodec> codecs;

    public DefaultReplayFormatDetector(List<ReplayStorageCodec> codecs) {
        this.codecs = List.copyOf(Objects.requireNonNull(codecs, "codecs"));
    }

    @Override
    public ReplayStorageCodec detectCodec(String replayName, byte[] storedBytes) {
        for (ReplayStorageCodec codec : codecs) {
            if (codec.canDecode(replayName, storedBytes)) {
                return codec;
            }
        }
        throw new IllegalArgumentException("No replay storage codec can decode replay payload: " + replayName);
    }
}