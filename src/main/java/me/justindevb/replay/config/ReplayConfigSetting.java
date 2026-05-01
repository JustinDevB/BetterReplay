package me.justindevb.replay.config;

import org.bukkit.configuration.file.FileConfiguration;

public enum ReplayConfigSetting {
        CONFIG_VERSION("Config-Version", 3,
            "Internal config migration version. Do not edit unless instructed."),
    CHECK_UPDATE("General.Check-Update", true,
            "Check for plugin updates on startup."),
    STORAGE_TYPE("General.Storage-Type", "file",
            "Storage backend to use: file or mysql."),
    MYSQL_HOST("General.MySQL.host", "host",
            "MySQL host name or IP address."),
    MYSQL_PORT("General.MySQL.port", 3306,
            "MySQL port."),
    MYSQL_DATABASE("General.MySQL.database", "database",
            "MySQL database/schema name."),
    MYSQL_USER("General.MySQL.user", "username",
            "MySQL username."),
    MYSQL_PASSWORD("General.MySQL.password", "password",
            "MySQL password."),
    PLAYBACK_SPEED_STEP("Playback.Speed-Step", 0.2,
            "Speed change increment per Faster/Slower click (e.g. 0.2 = 20%)."),
    PLAYBACK_MAX_SPEED("Playback.Max-Speed", 1.0,
            "Maximum playback speed multiplier. Must be >= 1.0."),
    RETENTION_ENABLED("Retention.Enabled", false,
            "Enable automatic deletion of old replays."),
    RETENTION_MAX_AGE("Retention.Max-Age", "30d",
            "Maximum age of a replay before it becomes eligible for retention cleanup."),
    RETENTION_CHECK_INTERVAL("Retention.Check-Interval", "1h",
            "How often the retention service scans for expired replays."),
    RETENTION_DELETE_PARTIAL_FAILURES("Retention.Delete-Partial-Failures", false,
            "Whether retention should continue deleting other expired replays after one delete fails."),
    RETENTION_LOG_DELETIONS("Retention.Log-Deletions", true,
            "Whether successful retention deletions are logged individually."),
    LIST_PAGE_SIZE("list-page-size", 10,
            "Number of replay names shown per /replay list page."),
    LIST_PROTECTED_HIGHLIGHT_COLOR("list-protected-highlight-color", "&6",
            "Legacy chat color code used to highlight protected replays in /replay list (for example &6). ");

    private final String key;
    private final Object defaultValue;
    private final String[] comments;

    ReplayConfigSetting(String key, Object defaultValue, String... comments) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.comments = comments != null ? comments : new String[0];
    }

    public String getKey() {
        return key;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public String[] getComments() {
        return comments;
    }

    public String getString(FileConfiguration config) {
        return config.getString(this.key, (String) this.defaultValue);
    }

    public boolean getBoolean(FileConfiguration config) {
        return config.getBoolean(this.key, (boolean) this.defaultValue);
    }

    public int getInt(FileConfiguration config) {
        return config.getInt(this.key, (int) this.defaultValue);
    }

    public double getDouble(FileConfiguration config) {
        return config.getDouble(this.key, ((Number) this.defaultValue).doubleValue());
    }
}
