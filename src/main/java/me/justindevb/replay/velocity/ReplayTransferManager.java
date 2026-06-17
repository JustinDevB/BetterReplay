package me.justindevb.replay.velocity;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import me.justindevb.replay.Replay;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ReplayTransferManager {

    public static final String CHANNEL = "betterreplay:proxy";
    public static final String START_REPLAY = "START_REPLAY";
    public static final String REQUEST_PENDING_REPLAY = "REQUEST_PENDING_REPLAY";
    public static final String REPLAY_TRANSFER_FAILED = "REPLAY_TRANSFER_FAILED";
    private static final long TRANSFER_FAILURE_TIMEOUT_TICKS = 200L;

    private final Replay plugin;
    private final Map<UUID, PendingReplayTransfer> pendingTransfers = new ConcurrentHashMap<>();

    public ReplayTransferManager(Replay plugin) {
        this.plugin = plugin;
    }

    public boolean requestReplayTransfer(Player player, String replayName, String targetServer) {
        PendingReplayTransfer pendingTransfer = new PendingReplayTransfer(targetServer);
        pendingTransfers.put(player.getUniqueId(), pendingTransfer);

        ByteArrayDataOutput out = ByteStreams.newDataOutput();

        out.writeUTF(START_REPLAY);
        out.writeUTF(replayName);
        out.writeUTF(targetServer);

        try {
            sendPluginMessage(player, out.toByteArray());
        } catch (RuntimeException ex) {
            pendingTransfers.remove(player.getUniqueId(), pendingTransfer);
            plugin.getLogger().log(Level.WARNING, "Failed to send replay transfer request for " + replayName, ex);
            sendTransferFailure(player, targetServer, "Failed to send the transfer request to the proxy.");
            return false;
        }

        scheduleTransferTimeout(player, pendingTransfer);
        return true;
    }

    public void requestPendingReplay(Player player) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();

        out.writeUTF(REQUEST_PENDING_REPLAY);

        out.writeUTF(player.getUniqueId().toString());

        try {
            sendPluginMessage(player, out.toByteArray());
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to request pending replay launch for " + player.getUniqueId(),
                    ex);
        }
    }

    public void completeReplayTransferFailure(Player player, String targetServer, String reason) {
        PendingReplayTransfer pendingTransfer = pendingTransfers.remove(player.getUniqueId());
        String resolvedTargetServer = normalizedOrFallback(targetServer,
                pendingTransfer != null ? pendingTransfer.targetServer() : "unknown");
        String resolvedReason = normalizedOrFallback(reason, "The proxy could not move you to that server.");
        sendTransferFailure(player, resolvedTargetServer, resolvedReason);
    }

    private void scheduleTransferTimeout(Player player, PendingReplayTransfer pendingTransfer) {
        plugin.getFoliaLib().getScheduler().runLater(() -> {
            if (!pendingTransfers.remove(player.getUniqueId(), pendingTransfer)) {
                return;
            }
            if (player.isOnline()) {
                sendTransferFailure(player, pendingTransfer.targetServer(), "No response was received from the proxy.");
            }
        }, TRANSFER_FAILURE_TIMEOUT_TICKS);
    }

    private void sendTransferFailure(Player player, String targetServer, String reason) {
        player.sendMessage("§cCould not connect to replay server §e" + targetServer + "§c. " + reason);
    }

    private void sendPluginMessage(Player player, byte[] message) {
        if (!requiresEntityScheduling(player)) {
            player.sendPluginMessage(plugin, CHANNEL, message);
            return;
        }

        CompletableFuture<?> sendTask = plugin.getFoliaLib().getScheduler()
                .runAtEntity(player, ignored -> player.sendPluginMessage(plugin, CHANNEL, message));
        sendTask.join();
    }

    private boolean requiresEntityScheduling(Player player) {
        if (plugin.getFoliaLib() == null || !plugin.getFoliaLib().isFolia()) {
            return false;
        }
        try {
            return !plugin.getFoliaLib().getScheduler().isOwnedByCurrentRegion(player);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private String normalizedOrFallback(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private record PendingReplayTransfer(String targetServer) {}
}
