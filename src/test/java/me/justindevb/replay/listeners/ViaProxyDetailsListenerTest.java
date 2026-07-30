package me.justindevb.replay.listeners;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ViaProxyDetailsListenerTest {
    private ViaProxyDetailsListener listener;
    private Player player;

    @BeforeEach
    void setUp() {
        listener = new ViaProxyDetailsListener(Logger.getAnonymousLogger());
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    }

    @Test
    void proxyDetails_withValidProtocolVersion_overridesPacketEventsVersion() {
        listener.onPluginMessageReceived(ViaProxyDetailsListener.CHANNEL, player,
                "{\"specVersion\":1,\"version\":775}".getBytes(StandardCharsets.UTF_8));

        assertEquals(ClientVersion.V_26_1, listener.resolveClientVersion(player, ClientVersion.V_1_7_2));
    }

    @Test
    void proxyDetails_withMalformedPayload_keepsPacketEventsVersion() {
        listener.onPluginMessageReceived(ViaProxyDetailsListener.CHANNEL, player,
                "not json".getBytes(StandardCharsets.UTF_8));

        assertEquals(ClientVersion.V_1_7_2, listener.resolveClientVersion(player, ClientVersion.V_1_7_2));
    }

    @Test
    void proxyDetails_withUnsupportedSpecification_keepsPacketEventsVersion() {
        listener.onPluginMessageReceived(ViaProxyDetailsListener.CHANNEL, player,
                "{\"specVersion\":2,\"version\":775}".getBytes(StandardCharsets.UTF_8));

        assertEquals(ClientVersion.V_1_7_2, listener.resolveClientVersion(player, ClientVersion.V_1_7_2));
    }

    @Test
    void proxyDetails_withNonIntegralVersion_keepsPacketEventsVersion() {
        listener.onPluginMessageReceived(ViaProxyDetailsListener.CHANNEL, player,
                "{\"specVersion\":1,\"version\":775.5}".getBytes(StandardCharsets.UTF_8));

        assertEquals(ClientVersion.V_1_7_2, listener.resolveClientVersion(player, ClientVersion.V_1_7_2));
    }
}
