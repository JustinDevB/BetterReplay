package me.justindevb.replay.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

public final class ItemStackSerializer {
    private ItemStackSerializer() {}

    public static String serializeItem(ItemStack item) {
        if (item == null) return null;

        try (
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)
        ) {
            dataOutput.writeObject(item);
            dataOutput.flush();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static ItemStack deserializeItem(Object obj) {
        if (!(obj instanceof String data) || data.isEmpty()) return null;

        try (
                ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
                BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)
        ) {
            Object read = dataInput.readObject();
            return read instanceof ItemStack item ? item : null;
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }
}
