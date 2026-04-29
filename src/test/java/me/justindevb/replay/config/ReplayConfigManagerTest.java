package me.justindevb.replay.config;

import me.justindevb.replay.Replay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplayConfigManagerTest {

    @Mock private Replay plugin;

    @TempDir Path tempDir;

    @Test
    void initialize_migratesLegacyCommentlessConfig_andSetsVersion() throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, """
                General:
                  Check-Update: true
                  Compress-Replays: true
                  Storage-Type: file
                  MySQL:
                    host: host
                    port: 3306
                    database: database
                    user: username
                    password: password
                list-page-size: 10
                """, StandardCharsets.UTF_8);

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getName()).thenReturn("BetterReplay");

        new ReplayConfigManager(plugin).initialize();

        String migrated = Files.readString(configFile, StandardCharsets.UTF_8);
        String nl = System.lineSeparator();
        assertTrue(migrated.startsWith("# ==========================================="));
        assertTrue(migrated.contains("# Internal config migration version. Do not edit unless instructed."));
        assertTrue(migrated.contains("Config-Version: 3"));
        assertFalse(migrated.contains("Compress-Replays:"));
        assertTrue(migrated.contains("# Check for plugin updates on startup."));
        assertTrue(migrated.contains("# Enable automatic deletion of old replays."));
        assertTrue(migrated.contains("Retention:"));
        assertFalse(migrated.contains("Enable-Benchmark-Command:"));
        assertTrue(migrated.contains("# Number of replay names shown per /replay list page."));
        assertTrue(migrated.indexOf("# MySQL host name or IP address.") < migrated.indexOf("host:"));
        assertTrue(migrated.indexOf("# Check for plugin updates on startup.") < migrated.indexOf("Check-Update:"));
        assertTrue(migrated.indexOf("Config-Version: 3") < migrated.indexOf("General:"));
        assertTrue(migrated.contains("Config-Version: 3" + nl + nl + "General:"));
        assertTrue(migrated.indexOf("password: password") < migrated.indexOf("# Number of replay names shown per /replay list page."));

        verify(plugin).reloadConfig();
    }

    @Test
    void initialize_isIdempotent_afterMigration() throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, """
                General:
                  Check-Update: true
                  Compress-Replays: true
                  Storage-Type: file
                  MySQL:
                    host: host
                    port: 3306
                    database: database
                    user: username
                    password: password
                list-page-size: 10
                """, StandardCharsets.UTF_8);

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getName()).thenReturn("BetterReplay");

        ReplayConfigManager manager = new ReplayConfigManager(plugin);
        manager.initialize();
        manager.initialize();

        String migrated = Files.readString(configFile, StandardCharsets.UTF_8);
        String nl = System.lineSeparator();
        String checkUpdateComment = "# Check for plugin updates on startup.";
        assertEquals(1, occurrencesOf(migrated, checkUpdateComment));
        assertEquals(1, occurrencesOf(migrated, "#         BetterReplay Configuration"));
        assertFalse(migrated.contains("Compress-Replays:"));
        assertTrue(migrated.indexOf("Config-Version: 3") < migrated.indexOf("General:"));
        assertTrue(migrated.contains("Config-Version: 3" + nl + nl + "General:"));
        assertFalse(migrated.contains("Config-Version: 3" + nl + nl + nl + "General:"));
    }

    @Test
    void initialize_clampsPlaybackMaxSpeed_toAtLeastOne() throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, """
                Playback:
                  Max-Speed: 0.5
                """, StandardCharsets.UTF_8);

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getName()).thenReturn("BetterReplay");

        new ReplayConfigManager(plugin).initialize();

        String migrated = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(migrated.contains("Max-Speed: 1.0"));
    }

    private int occurrencesOf(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while (true) {
            index = haystack.indexOf(needle, index);
            if (index < 0) {
                return count;
            }
            count++;
            index += needle.length();
        }
    }
}
