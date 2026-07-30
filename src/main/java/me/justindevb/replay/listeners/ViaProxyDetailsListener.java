package me.justindevb.replay.listeners;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Uses ViaVersion proxy details when a backend's local protocol detection cannot see the real client version.
 */
public final class ViaProxyDetailsListener implements PluginMessageListener, Listener {
    public static final String CHANNEL = "vv:proxy_details";
    private static final int SPEC_VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 1024;

    private final ConcurrentMap<UUID, Integer> clientProtocolVersions = new ConcurrentHashMap<>();
    private final Logger logger;

    public ViaProxyDetailsListener(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!CHANNEL.equals(channel) || message.length > MAX_PAYLOAD_BYTES) {
            return;
        }

        Integer protocolVersion = parseProtocolVersion(message);
        if (protocolVersion == null) {
            return;
        }

        clientProtocolVersions.put(player.getUniqueId(), protocolVersion);
    }

    public ClientVersion resolveClientVersion(Player player, ClientVersion fallback) {
        int protocolVersion = clientProtocolVersions.getOrDefault(player.getUniqueId(), fallback.getProtocolVersion());
        return ClientVersion.getById(protocolVersion);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clientProtocolVersions.remove(event.getPlayer().getUniqueId());
    }

    private Integer parseProtocolVersion(byte[] message) {
        try {
            JsonElement root = JsonParser.parseString(new String(message, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return null;
            }

            JsonObject payload = root.getAsJsonObject();
            JsonElement specVersion = payload.get("specVersion");
            JsonElement version = payload.get("version");
            if (specVersion == null || version == null || !specVersion.isJsonPrimitive() || !version.isJsonPrimitive()
                    || !specVersion.getAsJsonPrimitive().isNumber() || !version.getAsJsonPrimitive().isNumber()) {
                return null;
            }

            if (specVersion.getAsBigDecimal().intValueExact() != SPEC_VERSION) {
                return null;
            }

            int value = version.getAsBigDecimal().intValueExact();
            return value >= 0 ? value : null;
        } catch (RuntimeException ex) {
            logger.log(Level.FINE, "Ignoring malformed ViaVersion proxy details payload", ex);
            return null;
        }
    }
}
