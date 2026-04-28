package me.justindevb.replay.debug;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import me.justindevb.replay.Replay;
import me.justindevb.replay.api.ReplayManager;
import me.justindevb.replay.storage.ReplayInspection;
import me.justindevb.replay.storage.ReplayFormat;
import me.justindevb.replay.storage.ReplayStorage;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplayDebugCommandTest {

    @Mock private Replay replay;
    @Mock private ReplayManager replayManager;
    @Mock private ReplayStorage replayStorage;
    @Mock private FoliaLib foliaLib;
    @Mock private PlatformScheduler scheduler;
    @Mock private CommandSender sender;

    private ReplayDebugCommand command;

    @BeforeEach
    void setUp() {
        command = new ReplayDebugCommand(replay, replayManager, foliaLib, Logger.getLogger("ReplayDebugCommandTest"));
    }

    @Test
    void noPermission_rejected() {
        when(sender.hasPermission("replay.debug")).thenReturn(false);

        boolean handled = command.handle(sender, new String[]{"debug", "dump", "sample"});

        assertTrue(handled);
        verify(sender).sendMessage("You do not have permission");
    }

    @Test
    void validDump_withReplayNameContainingSpacesAndTickRange_startsAsyncDump() throws Exception {
        lenient().when(foliaLib.getScheduler()).thenReturn(scheduler);
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<WrappedTask> consumer = invocation.getArgument(0);
            consumer.accept(mock(WrappedTask.class));
            return null;
        }).when(scheduler).runNextTick(any());
        when(sender.hasPermission("replay.debug")).thenReturn(true);
        when(replay.getReplayStorage()).thenReturn(replayStorage);
        File dumpFile = Files.createTempFile("replay-dump", ".txt").toFile();
        dumpFile.deleteOnExit();
        when(replayStorage.getReplayDumpFile("demo replay", new ReplayDumpQuery(20, 40)))
                .thenReturn(CompletableFuture.completedFuture(dumpFile));

        boolean handled = command.handle(sender, new String[]{"debug", "dump", "demo", "replay", "start=20", "end=40"});

        assertTrue(handled);
        verify(sender).sendMessage("§eReplay dump started for: demo replay");
        verify(sender).sendMessage("§aReplay dump finished: " + dumpFile.getAbsolutePath());
    }

    @Test
    void malformedFilter_showsUsage() {
        when(sender.hasPermission("replay.debug")).thenReturn(true);

        command.handle(sender, new String[]{"debug", "dump", "demo", "start=-1"});

        verify(sender).sendMessage("§cstart filter requires a non-negative tick value");
        verify(sender).sendMessage("§cUsage: /replay debug dump <name> [start=<tick>] [end=<tick>]");
    }

    @Test
    void validInfo_withReplayNameContainingSpaces_printsMetadata() {
        lenient().when(foliaLib.getScheduler()).thenReturn(scheduler);
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<WrappedTask> consumer = invocation.getArgument(0);
            consumer.accept(mock(WrappedTask.class));
            return null;
        }).when(scheduler).runNextTick(any());
        when(sender.hasPermission("replay.debug")).thenReturn(true);
        when(replay.getReplayStorage()).thenReturn(replayStorage);
        when(replayStorage.getReplayInfo("demo replay")).thenReturn(CompletableFuture.completedFuture(
                new ReplayInspection("demo replay", ReplayFormat.BINARY_ARCHIVE, 3, 0, 10, 10, 0.5,
                        512, 256, 1024, 123456789L, "1.4.0", "1.4.0", 1, 1, true, 2)));

        boolean handled = command.handle(sender, new String[]{"debug", "info", "demo", "replay"});

        assertTrue(handled);
        verify(sender).sendMessage("§eReplay info started for: demo replay");
        verify(sender).sendMessage("§6Replay info: §fdemo replay");
        verify(sender).sendMessage(org.mockito.ArgumentMatchers.contains("§7Records: §f3"));
        verify(sender).sendMessage(org.mockito.ArgumentMatchers.contains("§7Stored size: §f512 B"));
    }

    @Test
    void tabComplete_suggestsDumpAndTickKeys() {
        when(sender.hasPermission("replay.debug")).thenReturn(true);
        when(replayManager.getCachedReplayNames()).thenReturn(List.of("alpha", "beta replay"));

        List<String> subcommands = command.tabComplete(sender, new String[]{"debug", "d"});
        List<String> names = command.tabComplete(sender, new String[]{"debug", "dump", "b"});
        List<String> filters = command.tabComplete(sender, new String[]{"debug", "dump", "beta replay", "st"});
        List<String> infoNames = command.tabComplete(sender, new String[]{"debug", "info", "b"});

        assertEquals(List.of("dump"), subcommands);
        assertEquals(List.of("beta replay"), names);
        assertEquals(List.of("start="), filters);
        assertEquals(List.of("beta replay"), infoNames);
    }
}