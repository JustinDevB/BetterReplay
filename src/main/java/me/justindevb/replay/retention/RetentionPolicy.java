package me.justindevb.replay.retention;

import me.justindevb.replay.config.ReplayConfigSetting;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.util.logging.Logger;

public record RetentionPolicy(
        boolean enabled,
        Duration maxAge,
        Duration checkInterval,
        boolean continueAfterDeleteFailure,
        boolean logDeletions
) {

    private static final Duration MIN_CHECK_INTERVAL = Duration.ofMinutes(5);

    public static RetentionPolicy fromConfig(FileConfiguration config, Logger logger) {
        Duration defaultMaxAge = RetentionDurationParser.parse((String) ReplayConfigSetting.RETENTION_MAX_AGE.getDefaultValue());
        Duration defaultCheckInterval = RetentionDurationParser.parse((String) ReplayConfigSetting.RETENTION_CHECK_INTERVAL.getDefaultValue());

        Duration maxAge = parseDuration(config, ReplayConfigSetting.RETENTION_MAX_AGE, defaultMaxAge, logger);
        Duration checkInterval = parseDuration(config, ReplayConfigSetting.RETENTION_CHECK_INTERVAL, defaultCheckInterval, logger);
        if (checkInterval.compareTo(MIN_CHECK_INTERVAL) < 0) {
            logger.warning("Retention.Check-Interval is below the minimum of 5m; clamping to 5m.");
            checkInterval = MIN_CHECK_INTERVAL;
        }

        return new RetentionPolicy(
                ReplayConfigSetting.RETENTION_ENABLED.getBoolean(config),
                maxAge,
                checkInterval,
                ReplayConfigSetting.RETENTION_DELETE_PARTIAL_FAILURES.getBoolean(config),
                ReplayConfigSetting.RETENTION_LOG_DELETIONS.getBoolean(config));
    }

    private static Duration parseDuration(FileConfiguration config, ReplayConfigSetting setting, Duration fallback, Logger logger) {
        try {
            return RetentionDurationParser.parse(setting.getString(config));
        } catch (IllegalArgumentException ex) {
            logger.warning("Invalid retention duration for " + setting.getKey() + ": " + ex.getMessage()
                    + ". Falling back to " + setting.getDefaultValue() + ".");
            return fallback;
        }
    }
}