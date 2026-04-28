package me.justindevb.replay.export;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import me.justindevb.replay.api.ReplayExportQuery;
import me.justindevb.replay.api.ReplayManager;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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
class ReplayExportCommandTest {

    @Mock private ReplayManager replayManager;
    @Mock private FoliaLib foliaLib;
    @Mock private PlatformScheduler scheduler;
    @Mock private CommandSender sender;

    private ReplayExportCommand command;

    @BeforeEach
    void setUp() {
        command = new ReplayExportCommand(replayManager, foliaLib, Logger.getLogger("ReplayExportCommandTest"));
    }

    @Test
    void noPermission_rejected() {
        when(sender.hasPermission("replay.export")).thenReturn(false);

        boolean handled = command.handle(sender, new String[]{"export", "demo"});

        assertTrue(handled);
        verify(sender).sendMessage("You do not have permission");
    }

    @Test
    void validExport_withNamedFilters_startsAsyncExport() {
        lenient().when(foliaLib.getScheduler()).thenReturn(scheduler);
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<WrappedTask> consumer = invocation.getArgument(0);
            consumer.accept(mock(WrappedTask.class));
            return null;
        }).when(scheduler).runNextTick(any());
        when(sender.hasPermission("replay.export")).thenReturn(true);
        File exported = Path.of("exports", "demo.br").toFile();
        when(replayManager.getSavedReplayFile(org.mockito.ArgumentMatchers.eq("demo replay"), any(ReplayExportQuery.class)))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(exported)));

        boolean handled = command.handle(sender, new String[]{"export", "demo", "replay", "player=Alex", "start=20", "end=40"});

        assertTrue(handled);
        ArgumentCaptor<ReplayExportQuery> queryCaptor = ArgumentCaptor.forClass(ReplayExportQuery.class);
        verify(replayManager).getSavedReplayFile(org.mockito.ArgumentMatchers.eq("demo replay"), queryCaptor.capture());
        assertEquals(new ReplayExportQuery("Alex", 20, 40), queryCaptor.getValue());
        verify(sender).sendMessage("§eReplay export started for: demo replay");
        verify(sender).sendMessage("§aReplay export finished: " + exported.getAbsolutePath());
    }

    @Test
    void malformedFilter_showsUsage() {
        when(sender.hasPermission("replay.export")).thenReturn(true);

        command.handle(sender, new String[]{"export", "demo", "start=-1"});

        verify(sender).sendMessage("§cstart filter requires a non-negative tick value");
        verify(sender).sendMessage("§cUsage: /replay export <name> [player=<name|all>] [start=<tick>] [end=<tick>]");
    }

    @Test
    void tabComplete_suggestsReplayNamesAndFilterKeys() {
        when(sender.hasPermission("replay.export")).thenReturn(true);
        when(replayManager.getCachedReplayNames()).thenReturn(List.of("alpha", "beta replay"));

        List<String> replayCompletions = command.tabComplete(sender, new String[]{"export", "b"});
        List<String> filterCompletions = command.tabComplete(sender, new String[]{"export", "beta replay", "st"});

        assertEquals(List.of("beta replay"), replayCompletions);
        assertEquals(List.of("start="), filterCompletions);
    }
}