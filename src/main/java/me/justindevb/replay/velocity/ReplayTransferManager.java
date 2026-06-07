package me.justindevb.replay.velocity;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import me.justindevb.replay.Replay;
import me.justindevb.replay.config.ReplayConfigSetting;
import net.kyori.adventure.text.Component;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public class ReplayTransferManager {

    public static final String CHANNEL = "betterreplay:proxy";

    private final Replay plugin;

    public ReplayTransferManager(Replay plugin) {
        this.plugin = plugin;
    }

    public void requestReplayTransfer(Player player, String replayName, String targetServer) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();

        out.writeUTF("START_REPLAY");
        out.writeUTF(replayName);
        out.writeUTF(targetServer);

        player.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
    }

    public void requestPendingReplay(Player player) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();

        out.writeUTF("REQUEST_PENDING_REPLAY");

        out.writeUTF(player.getUniqueId().toString());

        player.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
    }
}
