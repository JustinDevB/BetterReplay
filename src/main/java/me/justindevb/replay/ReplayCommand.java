package me.justindevb.replay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

public class ReplayCommand implements CommandExecutor, TabCompleter {
    private final RecorderManager manager;

    public ReplayCommand(RecorderManager manager) {
        this.manager = manager;
    }

    //TODO: Refactor this mess to strictly utilize the API instead of plugin internals
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Must be a player to execute this command");
            return true;
        }

        if (args.length == 0) {
            sendHelp(p);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                if (!p.hasPermission("replay.start")) {
                    p.sendMessage("You do not have permission");
                    return true;
                }
                if (args.length < 3) {
                    p.sendMessage("§cUsage: /replay start <name> <player1 player2 ...> [durationSeconds]");
                    return true;
                }

                String sessionName = args[1];
                int duration = -1;

                try {
                    duration = Integer.parseInt(args[args.length - 1]);
                } catch (NumberFormatException ignored) {}

                int endIndex = (duration != -1 ? args.length - 1 : args.length);

                String[] playerNames = new String[endIndex - 2];
                System.arraycopy(args, 2, playerNames, 0, endIndex - 2);

                List<Player> targets = new ArrayList<>();
                for (String pn : playerNames) {
                    Player target = Bukkit.getPlayerExact(pn);
                    if (target != null) {
                        targets.add(target);
                    } else {
                        p.sendMessage("§cPlayer not found: " + pn);
                    }
                }

                if (targets.isEmpty()) {
                    p.sendMessage("§cNo valid players to record.");
                    return true;
                }

                if (manager.startSession(sessionName, targets, duration)) {
                    p.sendMessage("§aStarted recording session: " + sessionName + " (" +
                            (duration == -1 ? "∞" : duration + "s") + ")");
                } else {
                    p.sendMessage("§cSession with that name already exists!");
                }
            }
            case "stop" -> {
                if (!p.hasPermission("replay.stop")) {
                    p.sendMessage("You do not have permission");
                    return true;
                }
                if (args.length < 2) {
                    p.sendMessage("§c/replay stop <name>");
                    return true;
                }
                String sessionName = joinArgs(args, 1);
                if (manager.stopSession(sessionName, true)) {
                    p.sendMessage("§aStopped recording session: " + sessionName);
                } else {
                    p.sendMessage("§cNo active session with that name!");
                }
            }
            case "play" -> {
                if (!p.hasPermission("replay.play")) {
                    p.sendMessage("You do not have permission");
                    return true;
                }
                if (args.length < 2) {
                    p.sendMessage("§c/replay play <name>");
                    return true;
                }
                String replayName = joinArgs(args, 1);
                Replay.getInstance()
                        .getReplayManagerImpl()
                    .startReplay(replayName, p);

                return true;
            }

            case "list" -> {
                if (!p.hasPermission("replay.list")) {
                    p.sendMessage("You do not have permission");
                    return true;
                }

                int parsedPage = 1;
                if (args.length >= 2) {
                    try {
                        parsedPage = Math.max(1, Integer.parseInt(args[1]));
                    } catch (NumberFormatException ignored) {
                        p.sendMessage("§c/replay list [page]");
                        return true;
                    }
                }

                final int page = parsedPage;

                Replay.getInstance().getReplayStorage().listReplays()
                        .thenAccept(replays -> Bukkit.getScheduler().runTask(Replay.getInstance(), () -> {
                            if (replays.isEmpty()) {
                                p.sendMessage("§cNo replays found.");
                                return;
                            }

                            int perPage = Replay.getInstance().getConfig().getInt("list-page-size", 10);
                            int totalPages = (int) Math.ceil((double) replays.size() / perPage);

                            if (page > totalPages) {
                                p.sendMessage("§cPage out of range. Max page: " + totalPages);
                                return;
                            }

                            int from = (page - 1) * perPage;
                            int to = Math.min(from + perPage, replays.size());

                            p.sendMessage("§6Replays §7(Page " + page + "/" + totalPages + ")");
                            for (int i = from; i < to; i++) {
                                p.sendMessage("§e- §f" + replays.get(i));
                            }

                            Component navigation = Component.empty();

                            if (page > 1) {
                                navigation = navigation.append(
                                        Component.text("§e[Previous]")
                                                .clickEvent(ClickEvent.runCommand("/replay list " + (page - 1)))
                                                .hoverEvent(HoverEvent.showText(Component.text("Go to page " + (page - 1))))
                                );
                            } else {
                                navigation = navigation.append(Component.text("§7[Previous]"));
                            }

                            navigation = navigation.append(Component.text(" §8| "));

                            if (page < totalPages) {
                                navigation = navigation.append(
                                        Component.text("§e[Next]")
                                                .clickEvent(ClickEvent.runCommand("/replay list " + (page + 1)))
                                                .hoverEvent(HoverEvent.showText(Component.text("Go to page " + (page + 1))))
                                );
                            } else {
                                navigation = navigation.append(Component.text("§7[Next]"));
                            }

                            p.sendMessage(navigation);
                        }))
                        .exceptionally(ex -> {
                            Replay.getInstance().getLogger().log(Level.INFO, "Failed to print list");
                            ex.printStackTrace();
                            return null;
                        });

                return true;
            }

            case "delete" -> {
                if (!p.hasPermission("replay.delete")) {
                    p.sendMessage("You do not have permission");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("Usage: /replay delete <name>");
                    return true;
                }
                String name = joinArgs(args, 1);
                Replay.getInstance().getReplayManagerImpl().deleteSavedReplay(name)
                        .thenAccept(success -> {
                            Replay.getInstance().getFoliaLib().getScheduler().runNextTick(task -> {
                                if (success) {
                                    p.sendMessage("§aDeleted replay: " + name);
                                } else {
                                    p.sendMessage("§cReplay not found: " + name);
                                }
                            });
                        })
                        .exceptionally(ex -> {
                            ex.printStackTrace();
                            Replay.getInstance().getFoliaLib().getScheduler().runNextTick(task ->
                                    p.sendMessage("§cFailed to delete replay: " + name));
                            return null;
                        });
                        return true;
            }
            default -> {
                p.sendMessage("§cUnknown subcommand: §f" + args[0]);
                sendHelp(p);
            }
        }
        return true;
    }

    private void sendHelp(Player p) {
        p.sendMessage("§6§lBetterReplay Commands:");
        if (p.hasPermission("replay.start"))
            p.sendMessage("§e/replay start <name> <player1 player2 ...> [seconds] §7- Start recording");
        if (p.hasPermission("replay.stop")) {
            p.sendMessage("§e/replay stop <name> §7- Stop an active recording");
            var sessions = manager.getActiveSessions();
            if (!sessions.isEmpty()) {
                p.sendMessage("§7  Active: §f" + String.join("§7, §f", sessions.keySet()));
            }
        }
        if (p.hasPermission("replay.play"))
            p.sendMessage("§e/replay play <name> §7- Play a saved replay");
        if (p.hasPermission("replay.list"))
            p.sendMessage("§e/replay list [page] §7- List saved replays");
        if (p.hasPermission("replay.delete"))
            p.sendMessage("§e/replay delete <name> §7- Delete a saved replay");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();

            if (sender.hasPermission("replay.start")) completions.add("start");
            if (sender.hasPermission("replay.stop")) completions.add("stop");
            if (sender.hasPermission("replay.play")) completions.add("play");
            if (sender.hasPermission("replay.list")) completions.add("list");
            if (sender.hasPermission("replay.delete")) completions.add("delete");

            return completions.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }

        if (args.length >= 2 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("play"))) {
            if (!sender.hasPermission("replay." + args[0].toLowerCase()))
                return Collections.emptyList();

            List<String> cachedReplays = Replay.getInstance()
                    .getReplayCache()
                    .getReplays();

            String prefix = joinArgs(args, 1).toLowerCase();

            return cachedReplays.stream()
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .toList();
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("stop")) {
            if (!sender.hasPermission("replay.stop"))
                return Collections.emptyList();

            String prefix = joinArgs(args, 1).toLowerCase();

            return Replay.getInstance()
                    .getRecorderManager()
                    .getActiveSessions()
                    .keySet()
                    .stream()
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .toList();
        }


        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            return Collections.emptyList();
        }

        if (args.length >= 3 && args[0].equalsIgnoreCase("start")) {
            if (!sender.hasPermission("replay.start"))
                return Collections.emptyList();

            // Collect already-selected player names so we don't suggest them again
            // args[2..length-2] are already-selected players; current partial is args[length-1]
            java.util.Set<String> alreadySelected = new java.util.HashSet<>();
            for (int i = 2; i < args.length - 1; i++) {
                alreadySelected.add(args[i].toLowerCase());
            }

            String currentArg = args[args.length - 1].toLowerCase();

            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> !alreadySelected.contains(name.toLowerCase()))
                    .filter(name -> name.toLowerCase().startsWith(currentArg))
                    .toList();
        }

        return Collections.emptyList();
    }

    private String joinArgs(String[] args, int fromIndex) {
        if (fromIndex >= args.length) {
            return "";
        }
        return String.join(" ", Arrays.copyOfRange(args, fromIndex, args.length)).trim();
    }

}
