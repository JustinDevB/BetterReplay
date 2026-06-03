package me.justindevb.replay.velocity;

import me.justindevb.replay.Replay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class ReplayJoinListener implements Listener {

    private final Replay plugin;

    public ReplayJoinListener(Replay plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        plugin.getFoliaLib().getScheduler().runLater(() -> plugin.getTransferManager().requestPendingReplay(player), 20L);
    }
}
