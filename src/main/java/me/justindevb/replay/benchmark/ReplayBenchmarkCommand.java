package me.justindevb.replay.benchmark;

import com.tcoded.folialib.FoliaLib;
import org.bukkit.command.CommandSender;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class ReplayBenchmarkCommand {

    private final ReplayBenchmarkService benchmarkService;
    private final FoliaLib foliaLib;
    private final java.util.logging.Logger logger;

    public ReplayBenchmarkCommand(ReplayBenchmarkService benchmarkService, FoliaLib foliaLib, java.util.logging.Logger logger) {
        this.benchmarkService = benchmarkService;
        this.foliaLib = foliaLib;
        this.logger = logger;
    }

    public boolean handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("replay.benchmark")) {
            sender.sendMessage("You do not have permission");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /replay benchmark <run|last> [small|medium|large|all]");
            return true;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "run" -> {
                return handleRun(sender, args);
            }
            case "last" -> {
                benchmarkService.lastArtifacts().ifPresentOrElse(
                        artifacts -> sender.sendMessage("§aLast benchmark results: md=" + artifacts.markdownPath() + " json=" + artifacts.jsonPath()),
                        () -> sender.sendMessage("§cNo benchmark results have been generated yet."));
                return true;
            }
            default -> {
                sender.sendMessage("§cUsage: /replay benchmark <run|last> [small|medium|large|all]");
                return true;
            }
        }
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("replay.benchmark")) {
            return Collections.emptyList();
        }
        if (args.length == 2) {
            return List.of("run", "last").stream()
                    .filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && "run".equalsIgnoreCase(args[1])) {
            return java.util.stream.Stream.concat(ReplayBenchmarkPreset.ids().stream(), java.util.stream.Stream.of("all"))
                    .filter(option -> option.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return Collections.emptyList();
    }

    private boolean handleRun(CommandSender sender, String[] args) {
        if (benchmarkService.isRunning()) {
            sender.sendMessage("§cA replay benchmark is already running.");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /replay benchmark run <small|medium|large|all>");
            return true;
        }

        CompletableFuture<ReplayBenchmarkArtifacts> future;
        String preset = args[2].toLowerCase(Locale.ROOT);
        if ("all".equals(preset)) {
            future = benchmarkService.startAll();
        } else {
            ReplayBenchmarkPreset parsedPreset = ReplayBenchmarkPreset.parse(preset).orElse(null);
            if (parsedPreset == null) {
                sender.sendMessage("§cUnknown benchmark preset: " + args[2]);
                return true;
            }
            future = benchmarkService.startPreset(parsedPreset);
        }

        sender.sendMessage("§eReplay benchmark started asynchronously for preset: " + preset);
        future.whenComplete((artifacts, throwable) -> notifyCompletion(sender, artifacts, throwable));
        return true;
    }

    private void notifyCompletion(CommandSender sender, ReplayBenchmarkArtifacts artifacts, Throwable throwable) {
        foliaLib.getScheduler().runNextTick(task -> {
            if (throwable != null) {
                logger.log(java.util.logging.Level.SEVERE, "Replay benchmark failed", throwable);
                sender.sendMessage("§cReplay benchmark failed: " + throwable.getMessage());
                return;
            }
            sender.sendMessage("§aReplay benchmark finished. Markdown: " + formatPath(artifacts.markdownPath())
                    + " JSON: " + formatPath(artifacts.jsonPath()));
        });
    }

    private static String formatPath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}