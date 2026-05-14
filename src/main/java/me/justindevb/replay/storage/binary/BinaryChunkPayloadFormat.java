package me.justindevb.replay.storage.binary;

import java.util.Objects;

/**
 * Supported uncompressed chunk payload families stored inside chunk region entries.
 */
public enum BinaryChunkPayloadFormat {
    BRCS("BRCS", 1),
    BRCP("BRCP", 1);

    private final String manifestValue;
    private final int currentVersion;

    BinaryChunkPayloadFormat(String manifestValue, int currentVersion) {
        this.manifestValue = manifestValue;
        this.currentVersion = currentVersion;
    }

    public String manifestValue() {
        return manifestValue;
    }

    public int currentVersion() {
        return currentVersion;
    }

    public static BinaryChunkPayloadFormat fromManifestValue(String value) {
        String normalized = Objects.requireNonNull(value, "value").trim();
        for (BinaryChunkPayloadFormat format : values()) {
            if (format.manifestValue.equals(normalized)) {
                return format;
            }
        }
        throw new IllegalArgumentException("Unsupported chunk payload format: " + value);
    }

    public static BinaryChunkPayloadFormat legacyDefault() {
        return BRCS;
    }
}