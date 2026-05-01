package me.justindevb.replay.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.configuration.InvalidConfigurationException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal comment-preserving YAML wrapper for Bukkit configs.
 * It stores comments as temporary pseudo-keys during parse and restores them on save.
 */
public class CommentedFileConfiguration {

    private static final String COMMENT_MARKER = "_COMMENT_";

    private final Plugin plugin;
    private final File file;
    private YamlConfiguration yaml;
    private int commentIndex;

    public CommentedFileConfiguration(Plugin plugin, File file) {
        this.plugin = plugin;
        this.file = file;
        this.yaml = new YamlConfiguration();
        this.commentIndex = 0;
    }

    public void load() {
        ensureParentDirectory();
        if (!file.exists()) {
            this.yaml = new YamlConfiguration();
            this.commentIndex = 0;
            return;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read config file: " + file.getAbsolutePath(), e);
        }

        this.commentIndex = countCommentLines(lines);
        String transformed = transformCommentsToKeys(lines);
        try {
            YamlConfiguration loaded = new YamlConfiguration();
            loaded.loadFromString(transformed);
            this.yaml = loaded;
        } catch (InvalidConfigurationException e) {
            throw new RuntimeException("Failed to parse config content", e);
        }
    }

    public boolean setIfNotExists(ReplayConfigSetting setting) {
        if (yaml.get(setting.getKey()) != null) {
            return false;
        }

        ensureSettingComments(setting);
        yaml.set(setting.getKey(), setting.getDefaultValue());
        return true;
    }

    public boolean ensureSettingComments(ReplayConfigSetting setting) {
        String prefix = settingPrefix(setting.getKey());
        boolean changed = false;

        for (String comment : setting.getComments()) {
            if (hasComment(prefix, comment)) {
                continue;
            }
            yaml.set(prefix + markerPrefix() + commentIndex++, " " + comment);
            changed = true;
        }

        return changed;
    }

    public void addHeaderComments(String... comments) {
        for (String comment : comments) {
            yaml.set(markerPrefix() + commentIndex++, " " + comment);
        }
    }

    public boolean addHeaderCommentsIfMissing(String... comments) {
        boolean changed = false;
        for (String comment : comments) {
            if (hasComment("", comment)) {
                continue;
            }
            yaml.set(markerPrefix() + commentIndex++, " " + comment);
            changed = true;
        }
        return changed;
    }

    public int getInt(String path, int defaultValue) {
        return yaml.getInt(path, defaultValue);
    }

    public double getDouble(String path, double defaultValue) {
        return yaml.getDouble(path, defaultValue);
    }

    public boolean contains(String path) {
        return yaml.contains(path);
    }

    public Object get(String path) {
        return yaml.get(path);
    }

    public void set(String path, Object value) {
        yaml.set(path, value);
    }

    public boolean setIfDifferent(String path, Object value) {
        Object current = yaml.get(path);
        if (Objects.equals(current, value)) {
            return false;
        }
        yaml.set(path, value);
        return true;
    }

    public void save() {
        ensureParentDirectory();
        String rawYaml = yaml.saveToString();
        String restored = restoreComments(rawYaml);

        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            writer.write(restored);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write config file: " + file.getAbsolutePath(), e);
        }
    }

    private void ensureParentDirectory() {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    private int countCommentLines(List<String> lines) {
        int count = 0;
        for (String line : lines) {
            if (line.trim().startsWith("#")) {
                count++;
            }
        }
        return count;
    }

    private String transformCommentsToKeys(List<String> lines) {
        StringBuilder out = new StringBuilder();
        int idx = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("#")) {
                out.append(line).append('\n');
                continue;
            }

            int indent = line.indexOf(trimmed);
            String indentStr = indent > 0 ? line.substring(0, indent) : "";
            String escaped = line.replace("'", "''");
            String pseudo = escaped.replaceFirst("#", plugin.getName() + COMMENT_MARKER + idx++ + ": '") + "'";
            out.append(indentStr).append(pseudo.trim()).append('\n');
        }

        return out.toString();
    }

    private String restoreComments(String yamlContent) {
        String[] lines = yamlContent.split("\\r?\\n", -1);
        List<String> restored = new ArrayList<>(lines.length);
        String markerPrefix = plugin.getName() + COMMENT_MARKER;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (!trimmed.startsWith(markerPrefix)) {
                restored.add(line);
                continue;
            }

            int indent = line.indexOf(trimmed);
            String indentStr = indent > 0 ? line.substring(0, indent) : "";
            int colon = line.indexOf(':');
            if (colon < 0 || colon + 2 >= line.length()) {
                continue;
            }

            String value = line.substring(colon + 1).trim();

            if (value.startsWith("'")) {
                value = value.substring(1);

                // SnakeYAML may wrap long single-quoted values onto continuation lines.
                while (!value.endsWith("'") && i + 1 < lines.length) {
                    i++;
                    value += " " + lines[i].trim();
                }
            }
            if (value.endsWith("'")) {
                value = value.substring(0, value.length() - 1);
            }
            value = value.replace("''", "'");

            if (value.startsWith(" ")) {
                restored.add(indentStr + "#" + value);
            } else {
                restored.add(indentStr + "# " + value);
            }
        }

        return String.join(System.lineSeparator(), restored);
    }

    private String settingPrefix(String key) {
        int split = key.lastIndexOf('.');
        return split == -1 ? "" : key.substring(0, split + 1);
    }

    private String markerPrefix() {
        return plugin.getName() + COMMENT_MARKER;
    }

    private boolean hasComment(String prefix, String comment) {
        String keyPrefix = prefix + markerPrefix();
        String expectedValue = " " + comment;
        for (String key : yaml.getKeys(true)) {
            if (!key.startsWith(keyPrefix)) {
                continue;
            }
            Object value = yaml.get(key);
            if (expectedValue.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
