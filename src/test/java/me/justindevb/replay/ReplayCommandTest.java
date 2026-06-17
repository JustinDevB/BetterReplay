package me.justindevb.replay;

import me.justindevb.replay.api.ReplayManager;
import me.justindevb.replay.benchmark.ReplayBenchmarkCommand;
import me.justindevb.replay.config.ReplayConfigReloadResult;
import me.justindevb.replay.config.ReplayConfigSetting;
import me.justindevb.replay.debug.ReplayDebugCommand;
import me.justindevb.replay.export.ReplayExportCommand;
import me.justindevb.replay.storage.ReplayDeleteResult;
import me.justindevb.replay.storage.ReplayProtectionResult;
import me.justindevb.replay.storage.ReplayStorage;
import me.justindevb.replay.storage.ReplayStorageType;
import me.justindevb.replay.storage.ReplaySummary;
import me.justindevb.replay.velocity.ReplayTransferManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReplayCommandTest {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    @Mock private ReplayManager replayManager;
    @Mock private ReplayBenchmarkCommand replayBenchmarkCommand;
    @Mock private ReplayExportCommand replayExportCommand;
    @Mock private ReplayDebugCommand replayDebugCommand;
    @Mock private ReplayStorage replayStorage;
    @Mock private Player player;
    @Mock private Command command;

    private ReplayCommand replayCommand;

    @BeforeEach
    void setUp() {
        replayCommand = new ReplayCommand(replayManager, replayBenchmarkCommand, replayExportCommand, replayDebugCommand);
    }

    // ── Non-player sender ─────────────────────────────────────

    @Test
    void nonPlayerSender_rejected() {
        org.bukkit.command.CommandSender consoleSender = mock(org.bukkit.command.CommandSender.class);
        boolean result = replayCommand.onCommand(consoleSender, command, "replay", new String[]{});
        assertTrue(result);
        verify(consoleSender).sendMessage("Must be a player to execute this command");
    }

    @Test
    void benchmarkSubcommand_canRunFromConsole() {
        org.bukkit.command.CommandSender consoleSender = mock(org.bukkit.command.CommandSender.class);
        when(replayBenchmarkCommand.handle(consoleSender, new String[]{"benchmark", "run", "small"})).thenReturn(true);

        boolean result = replayCommand.onCommand(consoleSender, command, "replay", new String[]{"benchmark", "run", "small"});

        assertTrue(result);
        verify(replayBenchmarkCommand).handle(consoleSender, new String[]{"benchmark", "run", "small"});
        verify(consoleSender, org.mockito.Mockito.never()).sendMessage("Must be a player to execute this command");
    }

    @Test
    void exportSubcommand_canRunFromConsole() {
        org.bukkit.command.CommandSender consoleSender = mock(org.bukkit.command.CommandSender.class);
        when(replayExportCommand.handle(consoleSender, new String[]{"export", "demo"})).thenReturn(true);

        boolean result = replayCommand.onCommand(consoleSender, command, "replay", new String[]{"export", "demo"});

        assertTrue(result);
        verify(replayExportCommand).handle(consoleSender, new String[]{"export", "demo"});
        verify(consoleSender, never()).sendMessage("Must be a player to execute this command");
    }

    @Test
    void debugSubcommand_canRunFromConsole() {
        org.bukkit.command.CommandSender consoleSender = mock(org.bukkit.command.CommandSender.class);
        when(replayDebugCommand.handle(consoleSender, new String[]{"debug", "dump", "demo"})).thenReturn(true);

        boolean result = replayCommand.onCommand(consoleSender, command, "replay", new String[]{"debug", "dump", "demo"});

        assertTrue(result);
        verify(replayDebugCommand).handle(consoleSender, new String[]{"debug", "dump", "demo"});
        verify(consoleSender, never()).sendMessage("Must be a player to execute this command");
    }

    @Test
    void protectSubcommand_canRunFromConsole() {
        org.bukkit.command.CommandSender consoleSender = mock(org.bukkit.command.CommandSender.class);
        when(consoleSender.hasPermission("replay.protect")).thenReturn(true);
        when(replayManager.protectSavedReplay("demo", "console"))
                .thenReturn(CompletableFuture.completedFuture(ReplayProtectionResult.UPDATED));

        try (MockedStatic<Replay> replay = mockStatic(Replay.class)) {
            Replay plugin = immediateReplayPlugin();
            replay.when(Replay::getInstance).thenReturn(plugin);

            boolean result = replayCommand.onCommand(consoleSender, command, "replay", new String[]{"protect", "demo"});

            assertTrue(result);
            verify(replayManager).protectSavedReplay("demo", "console");
            verify(consoleSender).sendMessage("§aProtected replay: demo");
        }
    }

    @Test
    void unprotectSubcommand_canRunFromConsole() {
        org.bukkit.command.CommandSender consoleSender = mock(org.bukkit.command.CommandSender.class);
        when(consoleSender.hasPermission("replay.unprotect")).thenReturn(true);
        when(replayManager.unprotectSavedReplay("demo"))
                .thenReturn(CompletableFuture.completedFuture(ReplayProtectionResult.UPDATED));

        try (MockedStatic<Replay> replay = mockStatic(Replay.class)) {
            Replay plugin = immediateReplayPlugin();
            replay.when(Replay::getInstance).thenReturn(plugin);

            boolean result = replayCommand.onCommand(consoleSender, command, "replay", new String[]{"unprotect", "demo"});

            assertTrue(result);
            verify(replayManager).unprotectSavedReplay("demo");
            verify(consoleSender).sendMessage("§aUnprotected replay: demo");
        }
    }

    @Test
    void reloadSubcommand_canRunFromConsole() {
        org.bukkit.command.CommandSender consoleSender = mock(org.bukkit.command.CommandSender.class);
        when(consoleSender.hasPermission("replay.reload")).thenReturn(true);

        try (MockedStatic<Replay> replay = mockStatic(Replay.class)) {
            Replay plugin = mock(Replay.class);
            when(plugin.reloadRuntimeConfig()).thenReturn(ReplayConfigReloadResult.fromChangedSettings(
                    List.of(ReplayConfigSetting.RETENTION_MAX_AGE, ReplayConfigSetting.LIST_PAGE_SIZE), true));
            replay.when(Replay::getInstance).thenReturn(plugin);

            boolean result = replayCommand.onCommand(consoleSender, command, "replay", new String[]{"reload"});

            assertTrue(result);
            verify(plugin).reloadRuntimeConfig();
            verify(consoleSender).sendMessage("§aReloaded BetterReplay config.");
            verify(consoleSender).sendMessage("§7Retention service restarted for: Retention.Max-Age");
            verify(consoleSender).sendMessage("§7Applied immediately: List.Page-Size");
        }
    }

    // ── No args ───────────────────────────────────────────────

    @Test
    void noArgs_sendsHelp() {
        when(player.hasPermission(anyString())).thenReturn(false);
        replayCommand.onCommand(player, command, "replay", new String[]{});
        verify(player).sendMessage("§6§lBetterReplay Commands:");
    }

    // ── Start ─────────────────────────────────────────────────

    @Nested
    class Start {
        @Test
        void noPermission_rejected() {
            when(player.hasPermission("replay.start")).thenReturn(false);
            replayCommand.onCommand(player, command, "replay", new String[]{"start", "test", "Player1"});
            verify(player).sendMessage("You do not have permission");
        }

        @Test
        void missingArgs_showsUsage() {
            when(player.hasPermission("replay.start")).thenReturn(true);
            replayCommand.onCommand(player, command, "replay", new String[]{"start", "test"});
            verify(player).sendMessage("§cUsage: /replay start <name> <player1 player2 ...> [durationSeconds]");
        }

        @Test
        void validWithDuration_startsRecording() {
            when(player.hasPermission("replay.start")).thenReturn(true);
            Player target = mock(Player.class);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayerExact("Steve")).thenReturn(target);
                when(replayManager.startRecording(eq("mySession"), anyCollection(), eq(60))).thenReturn(true);

                replayCommand.onCommand(player, command, "replay",
                        new String[]{"start", "mySession", "Steve", "60"});

                verify(replayManager).startRecording(eq("mySession"), anyCollection(), eq(60));
            }
        }

        @Test
        void validWithoutDuration_defaultsToNegativeOne() {
            when(player.hasPermission("replay.start")).thenReturn(true);
            Player target = mock(Player.class);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayerExact("Steve")).thenReturn(target);
                when(replayManager.startRecording(eq("mySession"), anyCollection(), eq(-1))).thenReturn(true);

                replayCommand.onCommand(player, command, "replay",
                        new String[]{"start", "mySession", "Steve"});

                verify(replayManager).startRecording(eq("mySession"), anyCollection(), eq(-1));
            }
        }

        @Test
        void nonexistentPlayer_showsError() {
            when(player.hasPermission("replay.start")).thenReturn(true);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayerExact("Nobody")).thenReturn(null);

                replayCommand.onCommand(player, command, "replay",
                        new String[]{"start", "test", "Nobody"});

                verify(player).sendMessage(componentMatching("§cPlayer not found: Nobody"));
                verify(player).sendMessage("§cNo valid players to record.");
            }
        }

        @Test
        void invalidSessionName_showsValidationMessage() {
            when(player.hasPermission("replay.start")).thenReturn(true);

            replayCommand.onCommand(player, command, "replay", new String[]{"start", "bad/name", "Steve"});

            verify(player).sendMessage("§cRecording names may not contain any of \\ / : * ? \" < > | or §");
            verify(replayManager, never()).startRecording(anyString(), anyCollection(), anyInt());
        }

        @Test
        void duplicateSessionName_showsError() {
            when(player.hasPermission("replay.start")).thenReturn(true);
            Player target = mock(Player.class);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayerExact("Steve")).thenReturn(target);
                when(replayManager.startRecording(eq("dup"), anyCollection(), eq(-1))).thenReturn(false);

                replayCommand.onCommand(player, command, "replay",
                        new String[]{"start", "dup", "Steve"});

                verify(player).sendMessage("§cSession with that name already exists!");
            }
        }
    }

    // ── Stop ──────────────────────────────────────────────────

    @Nested
    class Stop {
        @Test
        void noPermission_rejected() {
            when(player.hasPermission("replay.stop")).thenReturn(false);
            replayCommand.onCommand(player, command, "replay", new String[]{"stop", "test"});
            verify(player).sendMessage("You do not have permission");
        }

        @Test
        void missingArgs_showsUsage() {
            when(player.hasPermission("replay.stop")).thenReturn(true);
            replayCommand.onCommand(player, command, "replay", new String[]{"stop"});
            verify(player).sendMessage("§c/replay stop <name>");
        }

        @Test
        void validStop_stopsRecording() {
            when(player.hasPermission("replay.stop")).thenReturn(true);
            when(replayManager.stopRecording("test-session", true)).thenReturn(true);

            replayCommand.onCommand(player, command, "replay", new String[]{"stop", "test-session"});
            verify(player).sendMessage(componentMatching("§aStopped recording session: test-session"));
        }

        @Test
        void nonExistentSession_showsError() {
            when(player.hasPermission("replay.stop")).thenReturn(true);
            when(replayManager.stopRecording("nope", true)).thenReturn(false);

            replayCommand.onCommand(player, command, "replay", new String[]{"stop", "nope"});
            verify(player).sendMessage("§cNo active session with that name!");
        }
    }

    @Nested
    class Reload {
        @Test
        void noPermission_rejected() {
            when(player.hasPermission("replay.reload")).thenReturn(false);

            replayCommand.onCommand(player, command, "replay", new String[]{"reload"});

            verify(player).sendMessage("You do not have permission");
        }

        @Test
        void reportsImmediateNewSessionFutureAndRestartRequiredChanges() {
            when(player.hasPermission("replay.reload")).thenReturn(true);

            try (MockedStatic<Replay> replay = mockStatic(Replay.class)) {
                Replay plugin = mock(Replay.class);
                when(plugin.reloadRuntimeConfig()).thenReturn(ReplayConfigReloadResult.fromChangedSettings(
                        List.of(
                                ReplayConfigSetting.PLAYBACK_RESTORE_VIEWER_STATE_ON_REJOIN,
                                ReplayConfigSetting.PLAYBACK_SPEED_STEP,
                                ReplayConfigSetting.CHECK_UPDATE,
                                ReplayConfigSetting.STORAGE_TYPE),
                        true));
                replay.when(Replay::getInstance).thenReturn(plugin);

                replayCommand.onCommand(player, command, "replay", new String[]{"reload"});

                verify(player).sendMessage("§aReloaded BetterReplay config.");
                verify(player).sendMessage("§7Retention service restarted.");
                verify(player).sendMessage("§7Applied immediately: Playback.Restore-Viewer-State-On-Rejoin");
                verify(player).sendMessage("§7Affects new recordings/replays only: Playback.Speed-Step");
                verify(player).sendMessage("§7Applies to future startup/manual checks: General.Check-Update");
                verify(player).sendMessage("§7Still requires restart: General.Storage-Type");
            }
        }

        @Test
        void noVisibleChanges_reportsThatExplicitly() {
            when(player.hasPermission("replay.reload")).thenReturn(true);

            try (MockedStatic<Replay> replay = mockStatic(Replay.class)) {
                Replay plugin = mock(Replay.class);
                when(plugin.reloadRuntimeConfig()).thenReturn(ReplayConfigReloadResult.fromChangedSettings(List.of(), true));
                replay.when(Replay::getInstance).thenReturn(plugin);

                replayCommand.onCommand(player, command, "replay", new String[]{"reload"});

                verify(player).sendMessage("§7Retention service restarted.");
                verify(player).sendMessage("§7No runtime-facing config value changes were detected.");
            }
        }
    }

    // ── Play ──────────────────────────────────────────────────

    @Nested
    class Play {
        @Test
        void noPermission_rejected() {
            when(player.hasPermission("replay.play")).thenReturn(false);
            replayCommand.onCommand(player, command, "replay", new String[]{"play", "r"});
            verify(player).sendMessage("You do not have permission");
        }

        @Test
        void missingArgs_showsUsage() {
            when(player.hasPermission("replay.play")).thenReturn(true);
            replayCommand.onCommand(player, command, "replay", new String[]{"play"});
            verify(player).sendMessage("§c/replay play <name> [server:<server>]");
        }

        @Test
        void validPlay_startsReplay() {
            when(player.hasPermission("replay.play")).thenReturn(true);
            try (MockedStatic<Replay> replay = mockStatic(Replay.class)) {
                Replay plugin = immediateReplayPlugin();
                when(plugin.getReplayStorage()).thenReturn(replayStorage);
                when(replayStorage.replayExists("test"))
                        .thenReturn(CompletableFuture.completedFuture(true));
                when(replayManager.startReplay("test", player))
                        .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
                replay.when(Replay::getInstance).thenReturn(plugin);

                replayCommand.onCommand(player, command, "replay", new String[]{"play", "test"});
                verify(replayManager).startReplay("test", player);
            }
        }

        @Test
        void validPlay_withConfiguredDefaultServer_requestsTransfer() {
            when(player.hasPermission("replay.play")).thenReturn(true);
            ReplayTransferManager transferManager = mock(ReplayTransferManager.class);
            try (MockedStatic<Replay> replay = mockStatic(Replay.class)) {
                Replay plugin = immediateReplayPlugin();
                when(plugin.getConfig()).thenReturn(configWithDefaultReplayServer("replays"));
                when(plugin.getReplayStorage()).thenReturn(replayStorage);
                when(plugin.getTransferManager()).thenReturn(transferManager);
                when(replayStorage.replayExists("test"))
                        .thenReturn(CompletableFuture.completedFuture(true));
                when(transferManager.requestReplayTransfer(player, "test", "replays")).thenReturn(true);
                replay.when(Replay::getInstance).thenReturn(plugin);

                replayCommand.onCommand(player, command, "replay", new String[]{"play", "test"});

                verify(transferManager).requestReplayTransfer(player, "test", "replays");
                verify(player).sendMessage(componentMatching("§aConnecting to replay server §ereplays§a..."));
                verify(replayManager, never()).startReplay(anyString(), any(Player.class));
            }
        }

        @Test
        void validPlay_withExplicitServer_overridesConfiguredDefaultServer() {
            when(player.hasPermission("replay.play")).thenReturn(true);
            ReplayTransferManager transferManager = mock(ReplayTransferManager.class);
            try (MockedStatic<Replay> replay = mockStatic(Replay.class)) {
                Replay plugin = immediateReplayPlugin();
                when(plugin.getReplayStorage()).thenReturn(replayStorage);
                when(plugin.getTransferManager()).thenReturn(transferManager);
                when(replayStorage.replayExists("test"))
                        .thenReturn(CompletableFuture.completedFuture(true));
                when(transferManager.requestReplayTransfer(player, "test", "requested-replays")).thenReturn(true);
                replay.when(Replay::getInstance).thenReturn(plugin);

                replayCommand.onCommand(player, command, "replay", new String[]{"play", "test", "server:requested-replays"});

                verify(transferManager).requestReplayTransfer(player, "test", "requested-replays");
                verify(player).sendMessage(componentMatching("§aConnecting to replay server §erequested-replays§a..."));
                verify(replayManager, never()).startReplay(anyString(), any(Player.class));
            }
        }

        @Test
        void invalidReplayName_showsValidationMessage() {
            when(player.hasPermission("replay.play")).thenReturn(true);

            replayCommand.onCommand(player, command, "replay", new String[]{"play", "bad/name"});

            verify(player).sendMessage("§cReplay names may not contain any of \\ / : * ? \" < > | or §");
            verify(replayManager, never()).startReplay(anyString(), any(Player.class));
            verify(replayStorage, never()).replayExists(anyString());
        }
    }

    // ── Delete ────────────────────────────────────────────────

    @Nested
    class Delete {
        @Test
        void noPermission_rejected() {
            when(player.hasPermission("replay.delete")).thenReturn(false);
            replayCommand.onCommand(player, command, "replay", new String[]{"delete", "r"});
            verify(player).sendMessage("You do not have permission");
        }

        @Test
        void missingArgs_showsUsage() {
            when(player.hasPermission("replay.delete")).thenReturn(true);
            replayCommand.onCommand(player, command, "replay", new String[]{"delete"});
            // Sender gets usage message
            verify(player).sendMessage("Usage: /replay delete <name>");
        }

        @Test
        void deleteSuccess_showsDeletedMessage() {
            when(player.hasPermission("replay.delete")).thenReturn(true);
            when(replayManager.deleteSavedReplay("demo"))
                    .thenReturn(CompletableFuture.completedFuture(ReplayDeleteResult.DELETED));

            try (MockedStatic<Replay> replay = mockStatic(Replay.class)) {
                Replay plugin = immediateReplayPlugin();
                replay.when(Replay::getInstance).thenReturn(plugin);

                replayCommand.onCommand(player, command, "replay", new String[]{"delete", "demo"});

                verify(player).sendMessage(componentMatching("§aDeleted replay: demo"));
            }
        }

        @Test
        void deleteProtected_showsProtectedMessage() {
            when(player.hasPermission("replay.delete")).thenReturn(true);
            when(replayManager.deleteSavedReplay("demo"))
                    .thenReturn(CompletableFuture.completedFuture(ReplayDeleteResult.PROTECTED));

            try (MockedStatic<Replay> replay = mockStatic(Replay.class)) {
                Replay plugin = immediateReplayPlugin();
                replay.when(Replay::getInstance).thenReturn(plugin);

                replayCommand.onCommand(player, command, "replay", new String[]{"delete", "demo"});

                verify(player).sendMessage(componentMatching("§cReplay is protected and must be unprotected before deletion: demo"));
            }
        }

        @Test
        void invalidReplayName_showsValidationMessage() {
            when(player.hasPermission("replay.delete")).thenReturn(true);

            replayCommand.onCommand(player, command, "replay", new String[]{"delete", "bad/name"});

            verify(player).sendMessage("§cReplay names may not contain any of \\ / : * ? \" < > | or §");
            verify(replayManager, never()).deleteSavedReplay(anyString());
        }
    }

    // ── List ──────────────────────────────────────────────────

    @Nested
    class ListCmd {
        @Test
        void noPermission_rejected() {
            when(player.hasPermission("replay.list")).thenReturn(false);
            replayCommand.onCommand(player, command, "replay", new String[]{"list"});
            verify(player).sendMessage("You do not have permission");
        }

        @Test
        void protectedReplay_usesConfiguredHighlightColor() {
            when(player.hasPermission("replay.list")).thenReturn(true);
            when(replayManager.listSavedReplaySummaries()).thenReturn(CompletableFuture.completedFuture(List.of(
                    new ReplaySummary("normal", Instant.EPOCH, 10L, false, null, null, ReplayStorageType.FILE),
                    new ReplaySummary("protected", Instant.EPOCH, 20L, true, Instant.EPOCH, "Steve", ReplayStorageType.FILE)
            )));

            try (MockedStatic<Replay> replay = mockStatic(Replay.class);
                 MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                Replay plugin = mock(Replay.class);
                org.bukkit.scheduler.BukkitScheduler bukkitScheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);

                when(plugin.getConfig()).thenReturn(configWithProtectedReplayColor("&c"));
                doAnswer(invocation -> {
                    Runnable runnable = invocation.getArgument(1);
                    runnable.run();
                    return null;
                }).when(bukkitScheduler).runTask(eq(plugin), any(Runnable.class));

                replay.when(Replay::getInstance).thenReturn(plugin);
                bukkit.when(Bukkit::getScheduler).thenReturn(bukkitScheduler);

                replayCommand.onCommand(player, command, "replay", new String[]{"list"});

                verify(replayManager).listSavedReplaySummaries();
                verify(player).sendMessage(componentMatching("§e- §fnormal"));
                verify(player).sendMessage(componentMatching("§e- §cprotected"));
            }
        }
    }

    // ── Unknown subcommand ────────────────────────────────────

    @Test
    void unknownSubcommand_showsError() {
        when(player.hasPermission(anyString())).thenReturn(false);
        replayCommand.onCommand(player, command, "replay", new String[]{"foobar"});
        verify(player).sendMessage(componentMatching("§cUnknown subcommand: §ffoobar"));
    }

    // ── Tab completion ────────────────────────────────────────

    @Nested
    class TabComplete {
        @Test
        void firstArg_showsAvailableSubcommands() {
            when(player.hasPermission("replay.start")).thenReturn(true);
            when(player.hasPermission("replay.stop")).thenReturn(true);
            when(player.hasPermission("replay.play")).thenReturn(false);
            when(player.hasPermission("replay.delete")).thenReturn(false);
            when(player.hasPermission("replay.list")).thenReturn(false);
            when(player.hasPermission("replay.protect")).thenReturn(false);
            when(player.hasPermission("replay.unprotect")).thenReturn(false);
            when(player.hasPermission("replay.reload")).thenReturn(true);

            List<String> completions = replayCommand.onTabComplete(player, command, "replay", new String[]{""});
            assertTrue(completions.contains("start"));
            assertTrue(completions.contains("stop"));
            assertTrue(completions.contains("reload"));
            assertFalse(completions.contains("play"));
            assertFalse(completions.contains("export"));
            assertFalse(completions.contains("benchmark"));
            assertFalse(completions.contains("debug"));
        }

        @Test
        void exportPrefix_delegatesTabCompletion() {
            when(replayExportCommand.tabComplete(player, new String[]{"export", "b"})).thenReturn(List.of("beta"));

            List<String> completions = replayCommand.onTabComplete(player, command, "replay", new String[]{"export", "b"});

            assertEquals(List.of("beta"), completions);
        }

        @Test
        void benchmarkPrefix_delegatesTabCompletion() {
            when(replayBenchmarkCommand.tabComplete(player, new String[]{"benchmark", "r"})).thenReturn(List.of("run"));

            List<String> completions = replayCommand.onTabComplete(player, command, "replay", new String[]{"benchmark", "r"});

            assertEquals(List.of("run"), completions);
        }

        @Test
        void debugPrefix_delegatesTabCompletion() {
            when(replayDebugCommand.tabComplete(player, new String[]{"debug", "d"})).thenReturn(List.of("dump"));

            List<String> completions = replayCommand.onTabComplete(player, command, "replay", new String[]{"debug", "d"});

            assertEquals(List.of("dump"), completions);
        }

        @Test
        void firstArg_filtersPrefix() {
            when(player.hasPermission("replay.start")).thenReturn(true);
            when(player.hasPermission("replay.stop")).thenReturn(true);
            when(player.hasPermission("replay.play")).thenReturn(true);
            when(player.hasPermission("replay.delete")).thenReturn(true);
            when(player.hasPermission("replay.list")).thenReturn(true);
            when(player.hasPermission("replay.protect")).thenReturn(true);
            when(player.hasPermission("replay.unprotect")).thenReturn(true);
            when(player.hasPermission("replay.reload")).thenReturn(true);

            List<String> completions = replayCommand.onTabComplete(player, command, "replay", new String[]{"st"});
            assertTrue(completions.contains("start"));
            assertTrue(completions.contains("stop"));
            assertFalse(completions.contains("play"));
        }

        @Test
        void stopSubcommand_suggestsActiveSessions() {
            when(player.hasPermission("replay.stop")).thenReturn(true);
            when(replayManager.getActiveRecordings()).thenReturn(List.of("session1", "session2"));

            List<String> completions = replayCommand.onTabComplete(player, command, "replay", new String[]{"stop", ""});
            assertTrue(completions.contains("session1"));
            assertTrue(completions.contains("session2"));
        }

        @Test
        void playSubcommand_suggestsCachedReplays() {
            when(player.hasPermission("replay.play")).thenReturn(true);
            when(replayManager.getCachedReplayNames()).thenReturn(List.of("replay1", "replay2"));

            List<String> completions = replayCommand.onTabComplete(player, command, "replay", new String[]{"play", ""});
            assertTrue(completions.contains("replay1"));
            assertTrue(completions.contains("replay2"));
        }

        @Test
        void deleteSubcommand_suggestsCachedReplays() {
            when(player.hasPermission("replay.delete")).thenReturn(true);
            when(replayManager.getCachedReplayNames()).thenReturn(List.of("r1", "r2"));

            List<String> completions = replayCommand.onTabComplete(player, command, "replay", new String[]{"delete", ""});
            assertTrue(completions.contains("r1"));
        }

        @Test
        void protectSubcommand_suggestsCachedReplays() {
            when(player.hasPermission("replay.protect")).thenReturn(true);
            when(replayManager.getCachedReplayNames()).thenReturn(List.of("r1", "r2"));

            List<String> completions = replayCommand.onTabComplete(player, command, "replay", new String[]{"protect", ""});
            assertTrue(completions.contains("r1"));
        }
    }

    @Nested
    class Protect {
        @Test
        void playerProtectSuccess_usesPlayerNameAsActor() {
            when(player.hasPermission("replay.protect")).thenReturn(true);
            when(player.getName()).thenReturn("Steve");
            when(replayManager.protectSavedReplay("demo", "Steve"))
                    .thenReturn(CompletableFuture.completedFuture(ReplayProtectionResult.UPDATED));

            try (MockedStatic<Replay> replay = mockStatic(Replay.class)) {
                Replay plugin = immediateReplayPlugin();
                replay.when(Replay::getInstance).thenReturn(plugin);

                replayCommand.onCommand(player, command, "replay", new String[]{"protect", "demo"});

                verify(replayManager).protectSavedReplay("demo", "Steve");
                verify(player).sendMessage(componentMatching("§aProtected replay: demo"));
            }
        }

        @Test
        void invalidReplayName_showsValidationMessage() {
            when(player.hasPermission("replay.protect")).thenReturn(true);

            replayCommand.onCommand(player, command, "replay", new String[]{"protect", "bad/name"});

            verify(player).sendMessage("§cReplay names may not contain any of \\ / : * ? \" < > | or §");
            verify(replayManager, never()).protectSavedReplay(anyString(), anyString());
        }
    }

    @Nested
    class Unprotect {
        @Test
        void playerUnprotectSuccess_showsMessage() {
            when(player.hasPermission("replay.unprotect")).thenReturn(true);
            when(replayManager.unprotectSavedReplay("demo"))
                    .thenReturn(CompletableFuture.completedFuture(ReplayProtectionResult.UPDATED));

            try (MockedStatic<Replay> replay = mockStatic(Replay.class)) {
                Replay plugin = immediateReplayPlugin();
                replay.when(Replay::getInstance).thenReturn(plugin);

                replayCommand.onCommand(player, command, "replay", new String[]{"unprotect", "demo"});

                verify(replayManager).unprotectSavedReplay("demo");
                verify(player).sendMessage(componentMatching("§aUnprotected replay: demo"));
            }
        }

        @Test
        void invalidReplayName_showsValidationMessage() {
            when(player.hasPermission("replay.unprotect")).thenReturn(true);

            replayCommand.onCommand(player, command, "replay", new String[]{"unprotect", "bad/name"});

            verify(player).sendMessage("§cReplay names may not contain any of \\ / : * ? \" < > | or §");
            verify(replayManager, never()).unprotectSavedReplay(anyString());
        }
    }

    @Test
    void help_includesReloadWhenPermitted() {
        when(player.hasPermission("replay.start")).thenReturn(false);
        when(player.hasPermission("replay.stop")).thenReturn(false);
        when(player.hasPermission("replay.play")).thenReturn(false);
        when(player.hasPermission("replay.list")).thenReturn(false);
        when(player.hasPermission("replay.delete")).thenReturn(false);
        when(player.hasPermission("replay.protect")).thenReturn(false);
        when(player.hasPermission("replay.unprotect")).thenReturn(false);
        when(player.hasPermission("replay.reload")).thenReturn(true);

        replayCommand.onCommand(player, command, "replay", new String[]{});

        verify(player).sendMessage("§e/replay reload §7- Reload config and restart retention tasks");
    }

    private Replay immediateReplayPlugin() {
        Replay plugin = mock(Replay.class);
        com.tcoded.folialib.FoliaLib foliaLib = mock(com.tcoded.folialib.FoliaLib.class);
        com.tcoded.folialib.impl.PlatformScheduler scheduler = mock(com.tcoded.folialib.impl.PlatformScheduler.class);
        lenient().when(plugin.getFoliaLib()).thenReturn(foliaLib);
        lenient().when(foliaLib.getScheduler()).thenReturn(scheduler);
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<?> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(scheduler).runNextTick(any());
        lenient().doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            long delayTicks = invocation.getArgument(1);
            if (delayTicks == 1L) {
                runnable.run();
            }
            return null;
        }).when(scheduler).runLater(any(Runnable.class), anyLong());
        lenient().when(plugin.getConfig()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
        return plugin;
    }

    private org.bukkit.configuration.file.FileConfiguration configWithProtectedReplayColor(String color) {
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("List.Page-Size", 10);
        config.set("List.Protected-Highlight-Color", color);
        return config;
    }

    private org.bukkit.configuration.file.FileConfiguration configWithDefaultReplayServer(String server) {
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("Velocity.Default-Replay-Server", server);
        return config;
    }

    private Component componentMatching(String expectedLegacy) {
        return org.mockito.ArgumentMatchers.argThat(component -> expectedLegacy.equals(LEGACY.serialize(component)));
    }
}
