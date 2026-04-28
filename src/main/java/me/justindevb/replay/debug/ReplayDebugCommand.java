package me.justindevb.replay.debug;

import com.tcoded.folialib.FoliaLib;
import me.justindevb.replay.Replay;
import me.justindevb.replay.api.ReplayManager;
import me.justindevb.replay.storage.ReplayInspection;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ReplayDebugCommand {

    private static final String DUMP_USAGE = "§cUsage: /replay debug dump <name> [start=<tick>] [end=<tick>]";
    private static final String INFO_USAGE = "§cUsage: /replay debug info <name>";
    private static final String ROOT_USAGE = "§cUsage: /replay debug <dump|info> ...";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withLocale(Locale.ROOT)
            .withZone(ZoneId.systemDefault());
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("0.00");

    private final Replay replay;
    private final ReplayManager replayManager;
    private final FoliaLib foliaLib;
    private final Logger logger;

    public ReplayDebugCommand(Replay replay, ReplayManager replayManager, FoliaLib foliaLib, Logger logger) {
        this.replay = replay;
        this.replayManager = replayManager;
        this.foliaLib = foliaLib;
        this.logger = logger;
    }

    public boolean handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("replay.debug")) {
            sender.sendMessage("You do not have permission");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ROOT_USAGE);
            return true;
        }

        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "dump" -> handleDump(sender, args);
            case "info" -> handleInfo(sender, args);
            default -> {
                sender.sendMessage(ROOT_USAGE);
                yield true;
            }
        };
    }

    private boolean handleDump(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(DUMP_USAGE);
            return true;
        }

        ParsedDumpRequest request;
        try {
            request = parseRequest(args);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("§c" + ex.getMessage());
            sender.sendMessage(DUMP_USAGE);
            return true;
        }

        CompletableFuture<File> future = replay.getReplayStorage().getReplayDumpFile(request.replayName(), request.query());
        sender.sendMessage("§eReplay dump started for: " + request.replayName());
        future.whenComplete((file, throwable) -> notifyCompletion(sender, request.replayName(), file, throwable));
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        ParsedInfoRequest request;
        try {
            request = parseInfoRequest(args);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("§c" + ex.getMessage());
            sender.sendMessage(INFO_USAGE);
            return true;
        }

        CompletableFuture<ReplayInspection> future = replay.getReplayStorage().getReplayInfo(request.replayName());
        sender.sendMessage("§eReplay info started for: " + request.replayName());
        future.whenComplete((info, throwable) -> notifyInfoCompletion(sender, request.replayName(), info, throwable));
        return true;
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("replay.debug")) {
            return Collections.emptyList();
        }
        if (args.length == 2) {
            return List.of("dump", "info").stream()
                    .filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && ("dump".equalsIgnoreCase(args[1]) || "info".equalsIgnoreCase(args[1]))) {
            return replayManager.getCachedReplayNames().stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length >= 4 && "dump".equalsIgnoreCase(args[1])) {
            String current = args[args.length - 1].toLowerCase(Locale.ROOT);
            Set<String> suggestions = new LinkedHashSet<>();
            suggestions.add("start=");
            suggestions.add("end=");

            for (int index = 3; index < args.length - 1; index++) {
                String token = args[index].toLowerCase(Locale.ROOT);
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

    private void notifyInfoCompletion(CommandSender sender, String replayName, ReplayInspection info, Throwable throwable) {
        foliaLib.getScheduler().runNextTick(task -> {
            if (throwable != null) {
                logger.log(Level.SEVERE, "Replay info failed for " + replayName, throwable);
                sender.sendMessage("§cReplay info failed: " + throwable.getMessage());
                return;
            }
            if (info == null) {
                sender.sendMessage("§cReplay not found or info failed: " + replayName);
                return;
            }

            sender.sendMessage("§6Replay info: §f" + info.replayName());
            sender.sendMessage("§7Format: §f" + formatName(info));
            sender.sendMessage("§7Recorded with: §f" + valueOrUnknown(info.recordedWithVersion()));
            sender.sendMessage("§7Minimum viewer: §f" + valueOrUnknown(info.minimumViewerVersion()));
            sender.sendMessage("§7Recording started: §f" + formatTimestamp(info.recordingStartedAtEpochMillis()));
            sender.sendMessage("§7Records: §f" + info.recordCount() + " §8| §7Unique actors: §f" + info.uniqueActorCount()
                    + " §8| §7Worlds: §f" + info.uniqueWorldCount());
            sender.sendMessage("§7Ticks: §f" + info.startTick() + " -> " + info.endTick() + " §8| §7Length: §f"
                    + info.durationTicks() + " ticks (" + formatSeconds(info.durationSeconds()) + "s)");
            sender.sendMessage("§7Stored size: §f" + formatBytes(info.storedBytes()) + " §8| §7Compressed payload: §f"
                    + formatBytes(info.compressedPayloadBytes()) + " §8| §7Decompressed payload: §f"
                    + formatBytes(info.decompressedPayloadBytes()));
            sender.sendMessage("§7Compression ratio: §f" + formatCompressionRatio(info));
            sender.sendMessage("§7Seek index: §f" + (info.indexedPayload() ? ("yes (" + info.seekCheckpointCount() + " checkpoints)") : "no"));
        });
    }

    private void notifyCompletion(CommandSender sender, String replayName, File file, Throwable throwable) {
        foliaLib.getScheduler().runNextTick(task -> {
            if (throwable != null) {
                logger.log(Level.SEVERE, "Replay dump failed for " + replayName, throwable);
                sender.sendMessage("§cReplay dump failed: " + throwable.getMessage());
                return;
            }
            if (file == null || !file.exists()) {
                sender.sendMessage("§cReplay not found or dump failed: " + replayName);
                return;
            }
            sender.sendMessage("§aReplay dump finished: " + file.getAbsolutePath());
        });
    }

    private static ParsedInfoRequest parseInfoRequest(String[] args) {
        List<String> nameTokens = new ArrayList<>();
        for (int index = 2; index < args.length; index++) {
            if (args[index].contains("=")) {
                throw new IllegalArgumentException("Replay info does not accept filters");
            }
            nameTokens.add(args[index]);
        }

        if (nameTokens.isEmpty()) {
            throw new IllegalArgumentException("Replay name is required");
        }
        return new ParsedInfoRequest(String.join(" ", nameTokens));
    }

    private static ParsedDumpRequest parseRequest(String[] args) {
        List<String> nameTokens = new ArrayList<>();
        Integer startTick = null;
        Integer endTick = null;

        boolean parsingFilters = false;
        for (int index = 2; index < args.length; index++) {
            String token = args[index];
            if (token.contains("=")) {
                parsingFilters = true;
                String[] parts = token.split("=", 2);
                String key = parts[0].toLowerCase(Locale.ROOT);
                String value = parts.length > 1 ? parts[1] : "";
                switch (key) {
                    case "start" -> startTick = parseTick("start", value);
                    case "end" -> endTick = parseTick("end", value);
                    default -> throw new IllegalArgumentException("Unknown dump filter: " + key);
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

        return new ParsedDumpRequest(String.join(" ", nameTokens), new ReplayDumpQuery(startTick, endTick));
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

    private record ParsedDumpRequest(String replayName, ReplayDumpQuery query) {
    }

    private record ParsedInfoRequest(String replayName) {
    }

    private static String formatName(ReplayInspection info) {
        return switch (info.format()) {
            case BINARY_ARCHIVE -> "binary (.br)";
            case JSON -> "json";
        };
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static String formatTimestamp(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0) {
            return "unknown";
        }
        return TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(epochMillis)) + " (" + epochMillis + ")";
    }

    private static String formatSeconds(double seconds) {
        return DECIMAL_FORMAT.format(seconds);
    }

    private static String formatCompressionRatio(ReplayInspection info) {
        if (info.compressedPayloadBytes() <= 0 || info.decompressedPayloadBytes() <= 0) {
            return "unknown";
        }
        double ratio = (double) info.decompressedPayloadBytes() / (double) info.compressedPayloadBytes();
        return DECIMAL_FORMAT.format(ratio) + "x";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return DECIMAL_FORMAT.format(bytes / 1024.0) + " KiB";
        }
        return DECIMAL_FORMAT.format(bytes / (1024.0 * 1024.0)) + " MiB";
    }
}