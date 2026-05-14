package me.justindevb.replay.chunk;

import me.justindevb.replay.config.ReplayConfigSetting;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;

/**
 * Typed chunk-capture settings read from config.
 */
public record ChunkCaptureConfig(
        boolean enabled,
        int radius,
        int captureIntervalTicks,
        int maxUniqueChunksPerRecording
) {

    public ChunkCaptureConfig {
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
        if (captureIntervalTicks <= 0) {
            throw new IllegalArgumentException("captureIntervalTicks must be positive");
        }
        if (maxUniqueChunksPerRecording <= 0) {
            throw new IllegalArgumentException("maxUniqueChunksPerRecording must be positive");
        }
    }

    public static ChunkCaptureConfig from(FileConfiguration config) {
        Objects.requireNonNull(config, "config");
        return new ChunkCaptureConfig(
                ReplayConfigSetting.CHUNK_CAPTURE_ENABLED.getBoolean(config),
                ReplayConfigSetting.CHUNK_CAPTURE_RADIUS.getInt(config),
                ReplayConfigSetting.CHUNK_CAPTURE_INTERVAL_TICKS.getInt(config),
                ReplayConfigSetting.CHUNK_CAPTURE_MAX_UNIQUE_CHUNKS.getInt(config));
    }

    public static ChunkCaptureConfig disabled() {
        return new ChunkCaptureConfig(false, 1, 20, 20_000);
    }
}