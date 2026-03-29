package me.justindevb.replay.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.util.UUID;

public class FloodgateHook {

    public static UUID getCorrectUUID(UUID uuid) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Floodgate");
        if (plugin == null || !plugin.isEnabled())
            return uuid;



        FloodgateApi api = FloodgateApi.getInstance();

        if (api.isFloodgatePlayer(uuid)) {
            FloodgatePlayer player = api.getPlayer(uuid);
            if (player != null)
                return player.getCorrectUniqueId();
        }
        return uuid;
    }
}
