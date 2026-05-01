package me.justindevb.replay.retention;

import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetentionPolicyTest {

    @Test
    void fromConfig_readsConfiguredDurations() {
        FileConfiguration config = mock(FileConfiguration.class);
        when(config.getBoolean("Retention.Enabled", false)).thenReturn(true);
        when(config.getString("Retention.Max-Age", "30d")).thenReturn("45d");
        when(config.getString("Retention.Check-Interval", "1h")).thenReturn("6h");
        when(config.getBoolean("Retention.Delete-Partial-Failures", false)).thenReturn(true);
        when(config.getBoolean("Retention.Log-Deletions", true)).thenReturn(false);

        RetentionPolicy policy = RetentionPolicy.fromConfig(config, Logger.getLogger("RetentionPolicyTest"));

        assertEquals(Duration.ofDays(45), policy.maxAge());
        assertEquals(Duration.ofHours(6), policy.checkInterval());
        assertEquals(true, policy.enabled());
        assertEquals(true, policy.continueAfterDeleteFailure());
        assertEquals(false, policy.logDeletions());
    }

    @Test
    void fromConfig_invalidOrTooSmallDurationsFallbackAndClamp() {
        FileConfiguration config = mock(FileConfiguration.class);
        when(config.getBoolean("Retention.Enabled", false)).thenReturn(true);
        when(config.getString("Retention.Max-Age", "30d")).thenReturn("bad");
        when(config.getString("Retention.Check-Interval", "1h")).thenReturn("1m");
        when(config.getBoolean("Retention.Delete-Partial-Failures", false)).thenReturn(false);
        when(config.getBoolean("Retention.Log-Deletions", true)).thenReturn(true);

        RetentionPolicy policy = RetentionPolicy.fromConfig(config, Logger.getLogger("RetentionPolicyTest"));

        assertEquals(Duration.ofDays(30), policy.maxAge());
        assertEquals(Duration.ofMinutes(5), policy.checkInterval());
    }
}