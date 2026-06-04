package me.justindevb.replay.metrics;

import me.justindevb.replay.config.ReplayConfigSetting;
import me.justindevb.replay.retention.RetentionDurationParser;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BStatsCharts {

    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(Duration.ofDays(1).getSeconds());

    private BStatsCharts() {
    }

    public static void register(Metrics metrics, FileConfiguration config) {
        for (ChartValue chart : buildChartValues(config)) {
            metrics.addCustomChart(new SimplePie(chart.id(), () -> chart.value()));
        }
    }

    static List<ChartValue> buildChartValues(FileConfiguration config) {
        List<ChartValue> charts = new ArrayList<>();

        charts.add(new ChartValue("general_storage_type", lowerCase(ReplayConfigSetting.STORAGE_TYPE.getString(config))));
        charts.add(new ChartValue("recording_chunk_capture_enabled", booleanValue(ReplayConfigSetting.CHUNK_CAPTURE_ENABLED.getBoolean(config))));
        if (ReplayConfigSetting.CHUNK_CAPTURE_ENABLED.getBoolean(config)) {
            charts.add(new ChartValue("recording_chunk_capture_radius", integerValue(ReplayConfigSetting.CHUNK_CAPTURE_RADIUS.getInt(config))));
        }

        charts.add(new ChartValue("playback_viewer_safety_mode", lowerCase(ReplayConfigSetting.PLAYBACK_VIEWER_SAFETY_MODE.getString(config))));
        charts.add(new ChartValue("playback_restore_viewer_location_on_stop", booleanValue(ReplayConfigSetting.PLAYBACK_RESTORE_VIEWER_LOCATION_ON_STOP.getBoolean(config))));
        charts.add(new ChartValue("playback_restore_viewer_gamemode_on_stop", booleanValue(ReplayConfigSetting.PLAYBACK_RESTORE_VIEWER_GAMEMODE_ON_STOP.getBoolean(config))));
        charts.add(new ChartValue("playback_restore_viewer_flight_on_stop", booleanValue(ReplayConfigSetting.PLAYBACK_RESTORE_VIEWER_FLIGHT_ON_STOP.getBoolean(config))));
        charts.add(new ChartValue("playback_chunk_mode", integerValue(ReplayConfigSetting.PLAYBACK_CHUNK_MODE.getInt(config))));
        charts.add(new ChartValue("playback_chunk_view_radius", integerValue(ReplayConfigSetting.PLAYBACK_CHUNK_VIEW_RADIUS.getInt(config))));
        charts.add(new ChartValue("playback_chunk_send_limit_per_tick", integerValue(ReplayConfigSetting.PLAYBACK_CHUNK_SEND_LIMIT_PER_TICK.getInt(config))));
        charts.add(new ChartValue("playback_chunk_clear_limit_per_tick", integerValue(ReplayConfigSetting.PLAYBACK_CHUNK_CLEAR_LIMIT_PER_TICK.getInt(config))));
        charts.add(new ChartValue("playback_vanish_viewer", booleanValue(ReplayConfigSetting.PLAYBACK_VANISH_VIEWER.getBoolean(config))));

        charts.add(new ChartValue("retention_enabled", booleanValue(ReplayConfigSetting.RETENTION_ENABLED.getBoolean(config))));
        if (!ReplayConfigSetting.RETENTION_ENABLED.getBoolean(config)) {
            charts.add(new ChartValue("retention_max_age_days", durationInDays(ReplayConfigSetting.RETENTION_MAX_AGE.getString(config))));
        }

        return List.copyOf(charts);
    }

    static String durationInDays(String durationValue) {
        return durationInDays(RetentionDurationParser.parse(durationValue));
    }

    static String durationInDays(Duration duration) {
        BigDecimal days = BigDecimal.valueOf(duration.getSeconds())
                .divide(SECONDS_PER_DAY, 2, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return days.toPlainString() + "d";
    }

    static String lowerCase(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.toLowerCase(Locale.ROOT);
    }

    static String booleanValue(boolean value) {
        return Boolean.toString(value);
    }

    static String integerValue(int value) {
        return Integer.toString(value);
    }

    record ChartValue(String id, String value) {
    }
}