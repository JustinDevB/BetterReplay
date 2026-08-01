package me.justindevb.replay.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class ReplayMessagesConfig {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private YamlConfiguration messages;

    public ReplayMessagesConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);

        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = loadDefaults();
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || loaded.contains(key)) continue;
            loaded.set(key, defaults.get(key));
            changed = true;
        }

        if (changed) {
            try {
                loaded.save(file);
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not update missing messages.yml keys: " + exception.getMessage());
            }
        }
        messages = loaded;
    }

    private YamlConfiguration loadDefaults() {
        try (InputStream resource = plugin.getResource("messages.yml")) {
            if (resource == null) return new YamlConfiguration();
            return YamlConfiguration.loadConfiguration(new InputStreamReader(resource, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not load bundled messages.yml defaults: " + exception.getMessage());
            return new YamlConfiguration();
        }
    }

    public Component component(String key, String fallback, String... replacements) {
        String value = messages.getString(key, fallback);
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            value = value.replace("%" + replacements[index] + "%", replacements[index + 1]);
        }
        return MINI_MESSAGE.deserialize(value);
    }
}
