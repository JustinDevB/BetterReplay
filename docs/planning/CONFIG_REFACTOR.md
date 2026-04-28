# Config System Refactor Proposal

## Current Approach

The plugin registers all config defaults programmatically in `Replay.initGeneralConfigSettings()`:

```java
private void initConfig() {
    initGeneralConfigSettings();
    getConfig().options().copyDefaults(true);
    saveConfig();
}

private void initGeneralConfigSettings() {
    FileConfiguration config = getConfig();
    config.addDefault("General.Check-Update", true);
    config.addDefault("General.Compress-Replays", true);
    config.addDefault("General.Storage-Type", "file");
    config.addDefault("General.MySQL.host", "host");
    config.addDefault("General.MySQL.port", 3306);
    config.addDefault("General.MySQL.database", "database");
    config.addDefault("General.MySQL.user", "username");
    config.addDefault("General.MySQL.password", "password");
}
```

### How it works

1. `addDefault()` registers default values in memory
2. `copyDefaults(true)` merges missing keys into the loaded config
3. `saveConfig()` writes the merged result to `plugins/BetterReplay/config.yml`

### Current read sites

| File | Key | Fallback |
|------|-----|----------|
| `Replay.java` | `General.Check-Update` | *(none — relies on addDefault)* |
| `Replay.java` | `General.Storage-Type` | *(none — relies on addDefault)* |
| `FileReplayStorage.java` | `General.Compress-Replays` | `true` |
| `MySQLReplayStorage.java` | `General.Compress-Replays` | `true` |
| `ReplayCommand.java` | `list-page-size` | `10` |

### Problems

- **No comments in config.yml.** SnakeYAML strips all comments when serializing. New keys appear in the file with zero context for the server admin.
- **addDefault + copyDefaults is fragile.** Readers that don't pass an explicit fallback silently get the in-memory default but if `addDefault()` is ever removed, they return `false`/`null`/`0` with no warning.
- **Key strings are scattered.** Config keys are bare strings duplicated between the write site (`addDefault`) and every read site, with no compile-time safety.

---

## Proposed Approach

### 1. Ship a commented `config.yml` resource

Create `src/main/resources/config.yml` with all defaults and inline comments:

```yaml
# ===========================================
#        BetterReplay Configuration
# ===========================================

General:
  # Check for plugin updates on startup
  Check-Update: true

  # GZIP compress replay data files to save disk space
  Compress-Replays: true

  # Storage backend: "file" or "mysql"
  Storage-Type: file

  # MySQL connection settings (only used when Storage-Type is "mysql")
  MySQL:
    host: localhost
    port: 3306
    database: betterreplay
    user: username
    password: password

Playback:
  # Amount to change playback speed per click (0.2 = 20% steps)
  # Minimum effective speed is one step above zero
  Speed-Step: 0.2

  # Maximum playback speed multiplier (1.0 = real-time)
  Max-Speed: 1.0
```

### 2. Use `saveDefaultConfig()` instead of `copyDefaults` + `saveConfig`

```java
private void initConfig() {
    saveDefaultConfig(); // copies resource config.yml only if file doesn't exist
}
```

- First install: the commented resource file is copied verbatim — comments preserved.
- Existing installs: the file is untouched. New keys added in updates use coded fallbacks.

### 3. Always pass explicit fallbacks at read sites

```java
// Before (fragile — depends on addDefault having been called)
getConfig().getBoolean("General.Check-Update")

// After (self-contained — works even if key is missing from file)
getConfig().getBoolean("General.Check-Update", true)
```

Every `getConfig().getXxx()` call should include the default value inline so it is never dependent on `addDefault()` having run first.

### 4. Remove `initGeneralConfigSettings()`

With the resource file providing first-run defaults and all read sites carrying explicit fallbacks, the `addDefault()` calls become redundant and can be deleted entirely.

---

## Migration Impact

| Concern | Impact |
|---------|--------|
| Existing servers | No change — their `config.yml` is not overwritten by `saveDefaultConfig()` |
| New servers | Get a fully commented config file on first startup |
| New config keys in updates | Work silently via coded fallbacks; admins can copy from the resource file or docs to customize |
| Code safety | Every read has an explicit default — no silent nulls or zeros |

### What changes

| File | Change |
|------|--------|
| `src/main/resources/config.yml` | **New** — commented default config |
| `Replay.java` | Replace `initConfig()` body with `saveDefaultConfig()`, delete `initGeneralConfigSettings()` |
| `Replay.java` | Add fallback to `General.Check-Update` read |
| `Replay.java` | Add fallback to `General.Storage-Type` reads |
| `ReplaySession.java` | *(already uses fallbacks — no change needed)* |
| `FileReplayStorage.java` | *(already uses fallback — no change needed)* |
| `MySQLReplayStorage.java` | *(already uses fallback — no change needed)* |
| `ReplayCommand.java` | *(already uses fallback — no change needed)* |

---

## Alternative: CommentedFileConfiguration Library (Best of Both Worlds)

Source: [NikV2/AnticheatBase](https://github.com/NikV2/AnticheatBase/tree/master/src/main/java/me/nik/anticheatbase/files/commentedfiles)

This approach preserves YAML comments while still allowing programmatic default management — combining the best aspects of both approaches above.

### How it works

The library consists of three classes:

1. **`CommentedConfigurationSection`** — Wraps Bukkit's `ConfigurationSection` with a delegate pattern. All standard `getBoolean()`, `getString()`, etc. calls are forwarded to the underlying Bukkit config. This means all existing config read code works unchanged.

2. **`CommentedFileConfigurationHelper`** — The comment preservation engine. On **read**, it converts YAML comments (`# text`) into fake key-value pairs (`PluginName_COMMENT_0: 'text'`). On **write**, it reverses the transformation back to `# text` with proper indentation and spacing. This round-trips comments through SnakeYAML without losing them.

3. **`CommentedFileConfiguration`** — Extends `CommentedConfigurationSection`, adds `set(path, value, comments...)` (set a value with attached comments) and `addComments(comments...)` (standalone comment block). Handles loading, saving, and reloading.

### The comment trick

```
# This is a comment about storage type     ← original file
Storage-Type: file

↓ on read, transformed to:

BetterReplay_COMMENT_0: ' This is a comment about storage type'   ← fake YAML key
Storage-Type: file

↓ SnakeYAML serializes normally (it sees keys, not comments)

↓ on write, transformed back to:

# This is a comment about storage type     ← restored comment
Storage-Type: file
```

### Config enum pattern

The AnticheatBase library pairs this with a `Setting` enum that centralizes all config keys, defaults, and comments in one place:

```java
public enum Setting {
    CHECK_UPDATE("General.Check-Update", true,
            "Check for plugin updates on startup"),
    COMPRESS_REPLAYS("General.Compress-Replays", true,
            "GZIP compress replay data files to save disk space"),
    STORAGE_TYPE("General.Storage-Type", "file",
            "Storage backend: \"file\" or \"mysql\""),
    MYSQL_HOST("General.MySQL.host", "localhost"),
    MYSQL_PORT("General.MySQL.port", 3306),
    // ...
    SPEED_STEP("Playback.Speed-Step", 0.2,
            "Amount to change playback speed per click (0.2 = 20% steps)"),
    MAX_SPEED("Playback.Max-Speed", 1.0,
            "Maximum playback speed multiplier (1.0 = real-time)");

    private final String key;
    private final Object defaultValue;
    private final String[] comments;
    private Object cachedValue;

    // getBoolean(), getInt(), getDouble(), getString() typed accessors
    // setIfNotExists(config) — writes default + comments if key is missing
    // loadValue() — lazy-loads and caches from config
    // reset() — clears cache (for reload support)
}
```

**Usage at read sites becomes:**
```java
// Before — scattered magic strings
getConfig().getBoolean("General.Check-Update")

// After — compile-time safe, self-documenting
Setting.CHECK_UPDATE.getBoolean()
```

### What this gives us over the static resource approach

| Capability | Static `config.yml` resource | CommentedFileConfiguration |
|------------|------------------------------|---------------------------|
| Comments preserved on first install | Yes | Yes |
| Comments preserved after update adds new keys | No — new keys use silent fallbacks, no comments appear | **Yes — new keys written with their comments** |
| Comments survive user edits + plugin save | Depends on whether plugin ever calls `saveConfig()` | **Yes — comments round-trip through saves** |
| Compile-time config key safety | No — still bare strings | **Yes — enum constants** |
| Cached values (avoid repeated YAML parsing) | No | **Yes — lazy load + cache** |
| Reload support | Requires re-reading file | **Built-in `reloadConfig()` + `reset()`** |
| Config migration on updates | Manual | **Automatic — `setIfNotExists()` in enum loop** |

### Trade-offs

- **Three extra classes to maintain.** The library is ~450 lines of code across the three files. It uses reflection to access SnakeYAML's `DumperOptions` for formatting control, which could break with major Bukkit API changes (though it already handles the 1.18.1+ field name change).
- **Comment encoding is a workaround, not a standard mechanism.** It works reliably but future SnakeYAML versions with native comment support could make this unnecessary.
- **Slight complexity increase.** The `Setting` enum pattern adds indirection but pays for itself in safety and maintainability as the config grows.

### Implementation for BetterReplay

1. Copy the three `commentedfiles` classes into `me.justindevb.replay.config` (or similar package), adjusting the package declaration
2. Create a `Setting` enum with all current and future config keys, defaults, and comment strings
3. Replace `initConfig()` with a loop over `Setting.values()` calling `setIfNotExists(config)` then `config.save()`
4. Replace all `getConfig().getXxx("key")` calls with `Setting.KEY.getXxx()`
5. Existing config files are migrated automatically — missing keys get added with comments, existing keys and any user comments are preserved
