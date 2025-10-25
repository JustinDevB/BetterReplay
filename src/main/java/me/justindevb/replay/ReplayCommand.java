package me.justindevb.replay;

import me.justindevb.replay.util.ReplayStorage;
import me.justindevb.replay.util.SpawnFakePlayer;
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

public class ReplayCommand implements CommandExecutor, TabCompleter {
    private final RecorderManager manager;
    private final ReplayStorage storage;

    public ReplayCommand(RecorderManager manager) {
        this.manager = manager;
        this.storage = Replay.getInstance().getReplayStorage();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Player only.");
            return true;
        }

        if (args.length == 0) {
            p.sendMessage("/replay start <name>");
            p.sendMessage("/replay stop");
            p.sendMessage("/replay play <name>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> {
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
                if (args.length < 2) {
                    p.sendMessage("§c/replay stop <name>");
                    return true;
                }
                if (manager.stopSession(args[1])) {
                    p.sendMessage("§aStopped recording session: " + args[1]);
                } else {
                    p.sendMessage("§cNo active session with that name!");
                }
            }
            case "play" -> {
                if (args.length < 2) {
                    p.sendMessage("§c/replay play <name>");
                    return true;
                }
                manager.replaySession(args[1], p);
            }

            case "list" -> {
                List<String> replays = storage.listReplays();
                if (replays.isEmpty())
                    sender.sendMessage("No saved replays");
                else {
                    sender.sendMessage("Saved replays:");
                    replays.forEach(name -> sender.sendMessage(" - " + name));
                }
            }

            case "delete" -> {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /replay delete <name>");
                    return true;
                }
                String name = args[1];
                if (storage.deleteReplay(name))
                    sender.sendMessage("Deleted replay: " + name);
                else
                    sender.sendMessage("Replay not found: " + name);
            }

            default -> sender.sendMessage("Unknown command");

            case "test" -> {
                new SpawnFakePlayer(p.getUniqueId(), p.getName(), p.getLocation(), p);

                return true;
            }

        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("list", "delete", "play").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("play"))) {
            return storage.listReplays().stream()
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }

        return Collections.emptyList();
    }
}
