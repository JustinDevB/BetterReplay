package me.justindevb.replay.velocity;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import me.justindevb.replay.Replay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

public class ReplayLaunchMessageListener implements PluginMessageListener {

    private final Replay plugin;

    public ReplayLaunchMessageListener(Replay plugin) {
        this.plugin = plugin;
    }


    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!channel.equals(ReplayTransferManager.CHANNEL))
            return;

        ByteArrayDataInput in = ByteStreams.newDataInput(message);

        String type = in.readUTF();

        if (!type.equals("REPLAY_LAUNCH"))
            return;

        String replayName = in.readUTF();

        plugin.getReplayManagerImpl().startReplay(replayName, player);
    }
}
