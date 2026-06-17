package me.justindevb.replay.velocity;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import me.justindevb.replay.Replay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplayTransferManagerTest {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    @Mock private Replay plugin;
    @Mock private Player player;
    @Mock private FoliaLib foliaLib;
    @Mock private PlatformScheduler scheduler;

    @BeforeEach
    void setUp() {
        lenient().when(plugin.getFoliaLib()).thenReturn(foliaLib);
        lenient().when(foliaLib.getScheduler()).thenReturn(scheduler);
        when(player.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void completeReplayTransferFailure_reportsProxyReasonToPlayer() {
        ReplayTransferManager manager = new ReplayTransferManager(plugin);
        manager.requestReplayTransfer(player, "demo", "replays");

        manager.completeReplayTransferFailure(player, "replays", "Server is offline.");

        verify(player).sendMessage(componentMatching("§cCould not connect to replay server §ereplays§c. Server is offline."));
    }

    @Test
    void requestReplayTransfer_reportsNoProxyResponseWhenTimeoutExpires() {
        AtomicReference<Runnable> timeout = new AtomicReference<>();
        doAnswer(invocation -> {
            timeout.set(invocation.getArgument(0));
            return null;
        }).when(scheduler).runLater(any(Runnable.class), eq(200L));
        when(player.isOnline()).thenReturn(true);
        ReplayTransferManager manager = new ReplayTransferManager(plugin);

        manager.requestReplayTransfer(player, "demo", "replays");
        timeout.get().run();

        verify(player).sendMessage(componentMatching("§cCould not connect to replay server §ereplays§c. No response was received from the proxy."));
    }

    @Test
    void requestReplayTransfer_reportsSendFailureImmediately() {
        ReplayTransferManager manager = new ReplayTransferManager(plugin);
        Logger logger = Logger.getLogger("ReplayTransferManagerTest");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        when(plugin.getLogger()).thenReturn(logger);
        doThrow(new IllegalStateException("channel unavailable"))
                .when(player).sendPluginMessage(eq(plugin), eq(ReplayTransferManager.CHANNEL), any(byte[].class));

        boolean requested = manager.requestReplayTransfer(player, "demo", "replays");

        assertFalse(requested);
        verify(player).sendMessage(componentMatching("§cCould not connect to replay server §ereplays§c. Failed to send the transfer request to the proxy."));
        verify(scheduler, never()).runLater(any(Runnable.class), anyLong());
    }

    @Test
    void requestReplayTransfer_schedulesPluginMessageOnViewerRegionWhenFoliaRequiresIt() {
        when(foliaLib.isFolia()).thenReturn(true);
        when(scheduler.isOwnedByCurrentRegion(player)).thenReturn(false);
        doAnswer(invocation -> {
            java.util.function.Consumer<?> consumer = invocation.getArgument(1);
            consumer.accept(null);
            return CompletableFuture.completedFuture(null);
        }).when(scheduler).runAtEntity(eq(player), any());
        ReplayTransferManager manager = new ReplayTransferManager(plugin);

        manager.requestReplayTransfer(player, "demo", "replays");

        verify(scheduler).runAtEntity(eq(player), any());
        verify(player).sendPluginMessage(eq(plugin), eq(ReplayTransferManager.CHANNEL), any(byte[].class));
    }

    private Component componentMatching(String expectedLegacy) {
        return org.mockito.ArgumentMatchers.argThat(component -> expectedLegacy.equals(LEGACY.serialize(component)));
    }
}
