package me.justindevb.replay.metrics;

import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BStatsChartsTest {

    @Test
    void buildChartValues_omitsConditionalChartsWhenFeaturesAreDisabled() {
        FileConfiguration config = mock(FileConfiguration.class);
        when(config.getString("General.Storage-Type", "file")).thenReturn("mysql");
        when(config.getBoolean("Recording.Chunk-Capture.Enabled", false)).thenReturn(false);
        when(config.getString("Playback.Viewer-Safety-Mode", "creative")).thenReturn("creative");
        when(config.getBoolean("Playback.Restore-Viewer-Location-On-Stop", true)).thenReturn(true);
        when(config.getBoolean("Playback.Restore-Viewer-GameMode-On-Stop", true)).thenReturn(true);
        when(config.getBoolean("Playback.Restore-Viewer-Flight-On-Stop", true)).thenReturn(true);
        when(config.getInt("Playback.Chunk-Mode", 1)).thenReturn(2);
        when(config.getInt("Playback.Chunk-View-Radius", 3)).thenReturn(5);
        when(config.getInt("Playback.Chunk-Send-Limit-Per-Tick", 1)).thenReturn(7);
        when(config.getInt("Playback.Chunk-Clear-Limit-Per-Tick", 1)).thenReturn(9);
        when(config.getBoolean("Playback.Vanish-Viewer", true)).thenReturn(false);
        when(config.getBoolean("Retention.Enabled", false)).thenReturn(true);

        List<BStatsCharts.ChartValue> chartValues = BStatsCharts.buildChartValues(config);

        assertTrue(chartValues.contains(new BStatsCharts.ChartValue("general_storage_type", "mysql")));
        assertTrue(chartValues.contains(new BStatsCharts.ChartValue("recording_chunk_capture_enabled", "false")));
        assertFalse(chartValues.stream().anyMatch(chart -> chart.id().equals("recording_chunk_capture_radius")));
        assertTrue(chartValues.contains(new BStatsCharts.ChartValue("retention_enabled", "true")));
        assertFalse(chartValues.stream().anyMatch(chart -> chart.id().equals("retention_max_age_days")));
    }

    @Test
    void durationInDays_formatsFractionalDurations() {
        assertEquals("1.5d", BStatsCharts.durationInDays("36h"));
        assertEquals("0.04d", BStatsCharts.durationInDays("1h"));
    }

    @Test
    void lowerCase_returnsUnknownForBlankValues() {
        assertEquals("unknown", BStatsCharts.lowerCase(""));
        assertEquals("creative", BStatsCharts.lowerCase("Creative"));
    }
}