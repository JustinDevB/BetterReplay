package me.justindevb.replay.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

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
        messages = YamlConfiguration.loadConfiguration(file);
    }

    public Component component(String key, String fallback, String... replacements) {
        String value = messages.getString(key, fallback);
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            value = value.replace("%" + replacements[index] + "%", replacements[index + 1]);
        }
        return MINI_MESSAGE.deserialize(value);
    }
}
