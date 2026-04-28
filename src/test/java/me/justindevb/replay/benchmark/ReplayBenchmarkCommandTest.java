package me.justindevb.replay.benchmark;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
class ReplayBenchmarkCommandTest {

    @Mock private ReplayBenchmarkService benchmarkService;
    @Mock private FoliaLib foliaLib;
    @Mock private PlatformScheduler scheduler;
    @Mock private CommandSender sender;

    private ReplayBenchmarkCommand command;

    @BeforeEach
    void setUp() {
        command = new ReplayBenchmarkCommand(benchmarkService, foliaLib, Logger.getLogger("ReplayBenchmarkCommandTest"));
    }

    @Test
    void noPermission_rejected() {
        when(sender.hasPermission("replay.benchmark")).thenReturn(false);

        boolean handled = command.handle(sender, new String[]{"benchmark", "run", "small"});

        assertTrue(handled);
        verify(sender).sendMessage("You do not have permission");
    }

    @Test
    void runAll_startsAsyncAndReportsPaths() {
        lenient().when(foliaLib.getScheduler()).thenReturn(scheduler);
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<WrappedTask> consumer = invocation.getArgument(0);
            consumer.accept(mock(WrappedTask.class));
            return null;
        }).when(scheduler).runNextTick(any());
        when(sender.hasPermission("replay.benchmark")).thenReturn(true);
        when(benchmarkService.isRunning()).thenReturn(false);
        ReplayBenchmarkArtifacts artifacts = new ReplayBenchmarkArtifacts(
                new ReplayBenchmarkReport(null, List.of()),
                Path.of("benchmarks", "bench.md"),
                Path.of("benchmarks", "bench.json"));
        when(benchmarkService.startAll()).thenReturn(CompletableFuture.completedFuture(artifacts));

        command.handle(sender, new String[]{"benchmark", "run", "all"});

        verify(sender).sendMessage("§eReplay benchmark started asynchronously for preset: all");
        verify(sender).sendMessage("§aReplay benchmark finished. Markdown: "
                + artifacts.markdownPath().toAbsolutePath().normalize()
                + " JSON: " + artifacts.jsonPath().toAbsolutePath().normalize());
    }

    @Test
    void tabCompleteExposesKnownSubcommandsOnlyWhenBenchmarkPrefixUsed() {
        when(sender.hasPermission("replay.benchmark")).thenReturn(true);

        List<String> completions = command.tabComplete(sender, new String[]{"benchmark", "r"});

        assertEquals(List.of("run"), completions);
    }
}