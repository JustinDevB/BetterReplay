package me.justindevb.replay;

import me.justindevb.replay.util.ReplayObject;
import me.justindevb.replay.util.storage.FileReplayStorage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ReplayCommand implements CommandExecutor, TabCompleter {
    private final RecorderManager manager;
   // private final FileReplayStorage storage;

    public ReplayCommand(RecorderManager manager) {
        this.manager = manager;
     //   this.storage = Replay.getInstance().getReplayStorage();
    }

    //TODO: Refactor this mess to strictly utilize the API instead of plugin internals
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Must be a player to execute this command");
            return true;
        }

        if (args.length == 0) {
            p.sendMessage("/replay start <name> <player> [durationSeconds]");
            p.sendMessage("/replay stop <name>");
            p.sendMessage("/replay play <name>");
            p.sendMessage("/replay delete <name>");
            p.sendMessage("/replay list");
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
                if (manager.stopSession(args[1], true)) {
                    p.sendMessage("§aStopped recording session: " + args[1]);
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
                //manager.replaySession(args[1], p);

                Replay.getInstance()
                        .getReplayManagerImpl()
                        .startReplay(args[1], p);

                return true;
            }

            case "list" -> {
                if (!p.hasPermission("replay.list")) {
                    p.sendMessage("You do not have permission");
                    return true;
                }
                Replay.getInstance().getReplayStorage().listReplays().thenAccept(replays -> {
                    Bukkit.getScheduler().runTask(Replay.getInstance(), () -> {
                        if (replays.isEmpty()) {
                            sender.sendMessage("No saved replays");
                        } else {
                            sender.sendMessage("Saved replays:");
                            replays.forEach(name -> sender.sendMessage(" - " + name));
                        }
                    });
                });


              /*  List<String> replays = storage.listReplays();
                if (replays.isEmpty())
                    sender.sendMessage("No saved replays");
                else {
                    sender.sendMessage("Saved replays:");
                    replays.forEach(name -> sender.sendMessage(" - " + name));
                }
               */
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
                String name = args[1];
                ReplayObject replayObject = new ReplayObject(name, null, Replay.getInstance().getReplayStorage());
                replayObject.delete()
                        .thenCompose(success -> Replay.getInstance().getReplayStorage().listReplays()
                                .thenApply(names -> {
                                    Replay.getInstance().getReplayCache().setReplays(names);
                                    return success;
                                }))
                        .thenAccept(success -> {
                            Bukkit.getScheduler().runTask(Replay.getInstance(), () -> {
                                if (success) {
                                    p.sendMessage("§aDeleted replay: " + name);
                                } else {
                                    p.sendMessage("§cReplay not found: " + name);
                                }
                            });
                        })
                        .exceptionally(ex -> {
                            ex.printStackTrace();
                            Bukkit.getScheduler().runTask(Replay.getInstance(), () ->
                                    p.sendMessage("§cFailed to delete replay: " + name));
                            return null;
                        });

                /*  if (storage.deleteReplay(name))
                    sender.sendMessage("Deleted replay: " + name);
                else
                    sender.sendMessage("Replay not found: " + name);
               */

            }
            default -> sender.sendMessage("Unknown command");
            /*  case "test" -> {
                new SpawnFakePlayer(p.getUniqueId(), p.getName(), p.getLocation(), p);

                return true;
            }
*/
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();

            if (sender.hasPermission("replay.list")) completions.add("list");
            if (sender.hasPermission("replay.delete")) completions.add("delete");
            if (sender.hasPermission("replay.play")) completions.add("play");
            if (sender.hasPermission("replay.start")) completions.add("start");

            return completions.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("play"))) {
            if (!sender.hasPermission("replay." + args[0].toLowerCase()))
                return Collections.emptyList();

            List<String> cachedReplays = Replay.getInstance()
                    .getReplayCache()
                    .getReplays();

            return cachedReplays.stream()
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("stop")) {
            if (!sender.hasPermission("replay.stop"))
                return Collections.emptyList();

            return Replay.getInstance()
                    .getRecorderManager()
                    .getActiveSessions()
                    .keySet()
                    .stream()
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }


        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            return Collections.emptyList();
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("start")) {
            if (!sender.hasPermission("replay.start"))
                return Collections.emptyList();

            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase()))
                    .toList();
        }

        return Collections.emptyList();
    }

}
