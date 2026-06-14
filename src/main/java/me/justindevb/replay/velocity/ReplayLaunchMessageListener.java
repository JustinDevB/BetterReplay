package me.justindevb.replay.velocity;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import me.justindevb.replay.Replay;
import me.justindevb.replay.api.events.ReplayStopEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ReplayLaunchMessageListener implements PluginMessageListener, Listener {

    private final Replay plugin;
    private final List<UUID> replayCache;
    private String originServer = null;

    public ReplayLaunchMessageListener(Replay plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        replayCache = new ArrayList<>();
    }


    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!channel.equals(ReplayTransferManager.CHANNEL))
            return;

        ByteArrayDataInput in = ByteStreams.newDataInput(message);

        String type = in.readUTF();

        if (type.equals(ReplayTransferManager.REPLAY_TRANSFER_FAILED)) {
            handleTransferFailure(player, in);
            return;
        }

        if (!type.equals("REPLAY_LAUNCH"))
            return;

        String replayName = in.readUTF();
        originServer = in.readUTF();

        if (!replayCache.contains(player.getUniqueId()))
            replayCache.add(player.getUniqueId());

        plugin.getReplayManagerImpl().startReplay(replayName, player);
    }

    private void handleTransferFailure(Player player, ByteArrayDataInput in) {
        String targetServer = "";
        String reason = "";
        try {
            targetServer = in.readUTF();
            reason = in.readUTF();
        } catch (IllegalStateException ignored) {
            // Older or malformed proxy responses still produce a clear player-facing failure below.
        }
        plugin.getTransferManager().completeReplayTransferFailure(player, targetServer, reason);
    }

    @EventHandler
    public void onReplayFinish(ReplayStopEvent event) {
        Player p = event.getViewer();
        if (replayCache.contains(p.getUniqueId()) && originServer != null) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();

            out.writeUTF("REPLAY_FINISHED");
            out.writeUTF(originServer);
            p.sendPluginMessage(plugin, ReplayTransferManager.CHANNEL, out.toByteArray());
            replayCache.remove(p.getUniqueId());
        }
    }
}
