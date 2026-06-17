package me.justindevb.replay.util;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ReplayMessages {

    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

    private ReplayMessages() {
    }

    public static void send(CommandSender sender, String legacyMessage) {
        if (sender instanceof Player player) {
            player.sendMessage(LEGACY_SERIALIZER.deserialize(legacyMessage));
            return;
        }
        sender.sendMessage(legacyMessage);
    }
}
