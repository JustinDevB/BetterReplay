package me.justindevb.replay.export;

import com.tcoded.folialib.FoliaLib;
import me.justindevb.replay.api.ReplayExportQuery;
import me.justindevb.replay.api.ReplayManager;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ReplayExportCommand {

    private static final String USAGE = "§cUsage: /replay export <name> [player=<name|all>] [start=<tick>] [end=<tick>]";

    private final ReplayManager replayManager;
    private final FoliaLib foliaLib;
    private final Logger logger;

    public ReplayExportCommand(ReplayManager replayManager, FoliaLib foliaLib, Logger logger) {
        this.replayManager = replayManager;
        this.foliaLib = foliaLib;
        this.logger = logger;
    }

    public boolean handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("replay.export")) {
            sender.sendMessage("You do not have permission");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(USAGE);
            return true;
        }

        ParsedExportRequest request;
        try {
            request = parseRequest(args);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("§c" + ex.getMessage());
            sender.sendMessage(USAGE);
            return true;
        }

        CompletableFuture<Optional<File>> future = replayManager.getSavedReplayFile(request.replayName(), request.query());
        sender.sendMessage("§eReplay export started for: " + request.replayName());
        future.whenComplete((file, throwable) -> notifyCompletion(sender, request.replayName(), file, throwable));
        return true;
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("replay.export")) {
            return Collections.emptyList();
        }
        if (args.length == 2) {
            return replayManager.getCachedReplayNames().stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length >= 3) {
            String current = args[args.length - 1].toLowerCase(Locale.ROOT);
            Set<String> suggestions = new LinkedHashSet<>();
            suggestions.add("player=all");
            suggestions.add("start=");
            suggestions.add("end=");

            for (int index = 2; index < args.length - 1; index++) {
                String token = args[index].toLowerCase(Locale.ROOT);
                if (token.startsWith("player=")) {
                    suggestions.remove("player=all");
                }
                if (token.startsWith("start=")) {
                    suggestions.remove("start=");
                }
                if (token.startsWith("end=")) {
                    suggestions.remove("end=");
                }
            }

            return suggestions.stream()
                    .filter(option -> option.startsWith(current))
                    .toList();
        }
        return Collections.emptyList();
    }

    private void notifyCompletion(CommandSender sender, String replayName, Optional<File> file, Throwable throwable) {
        foliaLib.getScheduler().runNextTick(task -> {
            if (throwable != null) {
                logger.log(Level.SEVERE, "Replay export failed for " + replayName, throwable);
                sender.sendMessage("§cReplay export failed: " + throwable.getMessage());
                return;
            }
            if (file == null || file.isEmpty()) {
                sender.sendMessage("§cReplay not found or export failed: " + replayName);
                return;
            }
            sender.sendMessage("§aReplay export finished: " + file.get().getAbsolutePath());
        });
    }

    private static ParsedExportRequest parseRequest(String[] args) {
        List<String> nameTokens = new ArrayList<>();
        String player = null;
        Integer startTick = null;
        Integer endTick = null;

        boolean parsingFilters = false;
        for (int index = 1; index < args.length; index++) {
            String token = args[index];
            if (token.contains("=")) {
                parsingFilters = true;
                String[] parts = token.split("=", 2);
                String key = parts[0].toLowerCase(Locale.ROOT);
                String value = parts.length > 1 ? parts[1] : "";
                switch (key) {
                    case "player" -> player = value;
                    case "start" -> startTick = parseTick("start", value);
                    case "end" -> endTick = parseTick("end", value);
                    default -> throw new IllegalArgumentException("Unknown export filter: " + key);
                }
            } else if (!parsingFilters) {
                nameTokens.add(token);
            } else {
                throw new IllegalArgumentException("Replay name must appear before filters");
            }
        }

        if (nameTokens.isEmpty()) {
            throw new IllegalArgumentException("Replay name is required");
        }

        String replayName = String.join(" ", nameTokens);
        return new ParsedExportRequest(replayName, new ReplayExportQuery(player, startTick, endTick));
    }

    private static Integer parseTick(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " filter requires a non-negative tick value");
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(label + " filter requires a non-negative tick value");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " filter requires a non-negative tick value");
        }
    }

    private record ParsedExportRequest(String replayName, ReplayExportQuery query) {
    }
}