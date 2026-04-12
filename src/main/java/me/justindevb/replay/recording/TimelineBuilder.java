package me.justindevb.replay.recording;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static me.justindevb.replay.util.io.ItemStackSerializer.serializeItem;

/**
 * Collects per-tick snapshots into the final timeline data structure.
 */
public class TimelineBuilder {

    private final List<Map<String, Object>> timeline = new ArrayList<>();

    public void addEvent(Map<String, Object> event) {
        timeline.add(event);
    }

    public List<Map<String, Object>> getTimeline() {
        return timeline;
    }

    /**
     * Serialize a player's full inventory into a map suitable for timeline storage.
     */
    public Map<String, Object> captureInventory(org.bukkit.entity.Player p) {
        Map<String, Object> invSnapshot = new HashMap<>();

        invSnapshot.put("mainHand", serializeItem(p.getInventory().getItemInMainHand()));
        invSnapshot.put("offHand", serializeItem(p.getInventory().getItemInOffHand()));

        List<String> armor = new ArrayList<>(4);
        armor.add(serializeItem(p.getInventory().getBoots()));
        armor.add(serializeItem(p.getInventory().getLeggings()));
        armor.add(serializeItem(p.getInventory().getChestplate()));
        armor.add(serializeItem(p.getInventory().getHelmet()));
        invSnapshot.put("armor", armor);

        List<String> contents = new ArrayList<>();
        for (ItemStack item : p.getInventory().getContents()) {
            contents.add(serializeItem(item));
        }
        invSnapshot.put("contents", contents);

        return invSnapshot;
    }

    public Map<String, Object> serializeLocation(org.bukkit.Location loc) {
        Map<String, Object> map = new HashMap<>();
        map.put("x", loc.getX());
        map.put("y", loc.getY());
        map.put("z", loc.getZ());
        map.put("yaw", loc.getYaw());
        map.put("pitch", loc.getPitch());
        map.put("world", loc.getWorld().getName());
        return map;
    }
}
