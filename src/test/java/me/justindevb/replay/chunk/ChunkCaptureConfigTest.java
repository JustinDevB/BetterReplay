package me.justindevb.replay.chunk;

import me.justindevb.replay.config.ReplayConfigSetting;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChunkCaptureConfigTest {

    @Mock
    private FileConfiguration config;

    @Test
    void readsChunkCaptureSettingsFromTypedConfigKeys() {
        when(config.getBoolean(ReplayConfigSetting.CHUNK_CAPTURE_ENABLED.getKey(), false)).thenReturn(false);
        when(config.getInt(ReplayConfigSetting.CHUNK_CAPTURE_RADIUS.getKey(), 1)).thenReturn(2);
        when(config.getInt(ReplayConfigSetting.CHUNK_CAPTURE_INTERVAL_TICKS.getKey(), 20)).thenReturn(40);
        when(config.getInt(ReplayConfigSetting.CHUNK_CAPTURE_MAX_UNIQUE_CHUNKS.getKey(), 20_000)).thenReturn(12_345);

        ChunkCaptureConfig chunkCaptureConfig = ChunkCaptureConfig.from(config);

        assertFalse(chunkCaptureConfig.enabled());
        assertEquals(2, chunkCaptureConfig.radius());
        assertEquals(40, chunkCaptureConfig.captureIntervalTicks());
        assertEquals(12_345, chunkCaptureConfig.maxUniqueChunksPerRecording());
    }
}