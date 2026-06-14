package me.justindevb.replay.config;

import org.bukkit.configuration.file.FileConfiguration;

public enum ReplayConfigSetting {
        CONFIG_VERSION("Config-Version", 8, ReplayConfigReloadScope.INTERNAL,
            "Internal config migration version. Do not edit unless instructed."),
    CHECK_UPDATE("General.Check-Update", true, ReplayConfigReloadScope.FUTURE_ONLY,
            "Check for plugin updates on startup."),
    STORAGE_TYPE("General.Storage-Type", "file", ReplayConfigReloadScope.RESTART_REQUIRED,
            "Storage backend to use: file or mysql."),
    MYSQL_HOST("General.MySQL.host", "host", ReplayConfigReloadScope.RESTART_REQUIRED,
            "MySQL host name or IP address."),
    MYSQL_PORT("General.MySQL.port", 3306, ReplayConfigReloadScope.RESTART_REQUIRED,
            "MySQL port."),
    MYSQL_DATABASE("General.MySQL.database", "database", ReplayConfigReloadScope.RESTART_REQUIRED,
            "MySQL database/schema name."),
    MYSQL_USER("General.MySQL.user", "username", ReplayConfigReloadScope.RESTART_REQUIRED,
            "MySQL username."),
    MYSQL_PASSWORD("General.MySQL.password", "password", ReplayConfigReloadScope.RESTART_REQUIRED,
            "MySQL password."),
    VELOCITY_DEFAULT_REPLAY_SERVER("Velocity.Default-Replay-Server", "", ReplayConfigReloadScope.IMMEDIATE,
            "Default Velocity backend used by /replay play when no server:<backend> argument is provided.",
            "Leave blank to play replays on the current server unless the command specifies a server."),
    CHUNK_CAPTURE_ENABLED("Recording.Chunk-Capture.Enabled", false, ReplayConfigReloadScope.NEW_SESSIONS_ONLY,
            "Enable chunk baseline capture for binary .br replays."),
    CHUNK_CAPTURE_RADIUS("Recording.Chunk-Capture.Radius", 1, ReplayConfigReloadScope.NEW_SESSIONS_ONLY,
            "Chunk radius around each tracked player used for baseline capture."),
    CHUNK_CAPTURE_INTERVAL_TICKS("Recording.Chunk-Capture.Capture-Interval-Ticks", 20, ReplayConfigReloadScope.NEW_SESSIONS_ONLY,
            "How often the recording recomputes the tracked chunk-interest window."),
    CHUNK_CAPTURE_MAX_UNIQUE_CHUNKS("Recording.Chunk-Capture.Max-Unique-Chunks-Per-Recording", 20000, ReplayConfigReloadScope.NEW_SESSIONS_ONLY,
            "Maximum number of unique chunks captured in one recording before truncation."),
    PLAYBACK_SPEED_STEP("Playback.Speed-Step", 0.2, ReplayConfigReloadScope.NEW_SESSIONS_ONLY,
            "Speed change increment per Faster/Slower click (e.g. 0.2 = 20%)."),
    PLAYBACK_MAX_SPEED("Playback.Max-Speed", 1.0, ReplayConfigReloadScope.NEW_SESSIONS_ONLY,
            "Maximum playback speed multiplier. Must be >= 1.0."),
    PLAYBACK_VIEWER_SAFETY_MODE("Playback.Viewer-Safety-Mode", "creative", ReplayConfigReloadScope.IMMEDIATE,
            "Safety mode applied to the real viewer when a replay starts.",
            "Set this to creative to protect moderators from live-world hazards after the replay teleports them.",
            "Set this to off to leave the viewer's current game mode unchanged."),
    PLAYBACK_VANISH_VIEWER("Playback.Vanish-Viewer", true, ReplayConfigReloadScope.IMMEDIATE,
            "Hide replay viewers from live players while playback is active.",
            "Leave this enabled on live servers so staff reviewing a replay do not appear inside protected builds.",
            "Set this to false on dedicated replay servers if viewer visibility is intentional."),
    PLAYBACK_RESTORE_VIEWER_LOCATION_ON_STOP("Playback.Restore-Viewer-Location-On-Stop", true, ReplayConfigReloadScope.IMMEDIATE,
            "Return the viewer to their original pre-replay location when playback ends.",
            "Leave this enabled unless you intentionally want replay playback to strand viewers at the replay location."),
    PLAYBACK_RESTORE_VIEWER_GAMEMODE_ON_STOP("Playback.Restore-Viewer-GameMode-On-Stop", true, ReplayConfigReloadScope.IMMEDIATE,
            "Restore the viewer's original game mode when playback ends.",
            "Leave this enabled so temporary replay safety mode changes do not leak back into normal gameplay."),
    PLAYBACK_RESTORE_VIEWER_FLIGHT_ON_STOP("Playback.Restore-Viewer-Flight-On-Stop", true, ReplayConfigReloadScope.IMMEDIATE,
            "Restore the viewer's original flight permissions when playback ends.",
            "This keeps creative-mode replay safety from leaving allow-flight or flying enabled unexpectedly."),
    PLAYBACK_RESTORE_VIEWER_STATE_ON_REJOIN("Playback.Restore-Viewer-State-On-Rejoin", true, ReplayConfigReloadScope.IMMEDIATE,
            "If a viewer disconnects during replay, restore their saved location and mode on the next join.",
            "Leave this enabled so logout during playback does not leave staff at a replay location or in the wrong mode."),
    PLAYBACK_CHUNK_MODE("Playback.Chunk-Mode", 1, ReplayConfigReloadScope.NEW_SESSIONS_ONLY,
            "Replay chunk playback mode: 1 restores live chunks as they leave the replay window; 2 defers live chunk restore until replay stop and resends replay chunks if they naturally unload and later return."),
    PLAYBACK_CHUNK_VIEW_RADIUS("Playback.Chunk-View-Radius", 3, ReplayConfigReloadScope.NEW_SESSIONS_ONLY,
            "Chunk radius around the replay viewer used for chunk snapshot playback. This is separate from recording chunk capture radius."),
    PLAYBACK_CHUNK_SEND_LIMIT_PER_TICK("Playback.Chunk-Send-Limit-Per-Tick", 1, ReplayConfigReloadScope.NEW_SESSIONS_ONLY,
            "Maximum number of replay chunks sent to the viewer per tick during packet-friendly chunk playback. Increase this on dedicated replay servers to fill larger chunk view radii faster."),
    PLAYBACK_CHUNK_CLEAR_LIMIT_PER_TICK("Playback.Chunk-Clear-Limit-Per-Tick", 1, ReplayConfigReloadScope.NEW_SESSIONS_ONLY,
            "Maximum number of live chunks restored to the viewer per tick during packet-friendly chunk teardown. Increase this on dedicated replay servers to clear larger chunk view radii faster."),
    PLAYBACK_CHUNK_TIMING_DIAGNOSTICS("Playback.Chunk-Timing-Diagnostics", false, ReplayConfigReloadScope.NEW_SESSIONS_ONLY,
            "Log replay chunk load and restore stage timings during playback for MSPT troubleshooting."),
    RETENTION_ENABLED("Retention.Enabled", false, ReplayConfigReloadScope.RETENTION_RESTART,
            "Enable automatic deletion of old replays."),
    RETENTION_MAX_AGE("Retention.Max-Age", "30d", ReplayConfigReloadScope.RETENTION_RESTART,
            "Maximum age of a replay before it becomes eligible for retention cleanup."),
    RETENTION_CHECK_INTERVAL("Retention.Check-Interval", "1h", ReplayConfigReloadScope.RETENTION_RESTART,
            "How often the retention service scans for expired replays."),
    RETENTION_DELETE_PARTIAL_FAILURES("Retention.Delete-Partial-Failures", false, ReplayConfigReloadScope.RETENTION_RESTART,
            "Whether retention should continue deleting other expired replays after one delete fails."),
    RETENTION_LOG_DELETIONS("Retention.Log-Deletions", true, ReplayConfigReloadScope.RETENTION_RESTART,
            "Whether successful retention deletions are logged individually."),
    LIST_PAGE_SIZE("List.Page-Size", 10, ReplayConfigReloadScope.IMMEDIATE,
            "Number of replay names shown per /replay list page."),
    LIST_PROTECTED_HIGHLIGHT_COLOR("List.Protected-Highlight-Color", "&6", ReplayConfigReloadScope.IMMEDIATE,
            "Chat color code used to highlight protected replays in /replay list (for example &6).");

    private final String key;
    private final Object defaultValue;
    private final ReplayConfigReloadScope reloadScope;
    private final String[] comments;

    ReplayConfigSetting(String key, Object defaultValue, ReplayConfigReloadScope reloadScope, String... comments) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.reloadScope = reloadScope;
        this.comments = comments != null ? comments : new String[0];
    }

    public String getKey() {
        return key;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

        public ReplayConfigReloadScope getReloadScope() {
                return reloadScope;
        }

    public String[] getComments() {
        return comments;
    }

        public Object readValue(FileConfiguration config) {
                if (defaultValue instanceof Boolean) {
                        return getBoolean(config);
                }
                if (defaultValue instanceof Integer) {
                        return getInt(config);
                }
                if (defaultValue instanceof Number) {
                        return getDouble(config);
                }
                return getString(config);
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
