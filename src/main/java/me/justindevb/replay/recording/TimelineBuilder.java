package me.justindevb.replay.recording;

import me.justindevb.replay.storage.ReplayAppendLogWriter;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static me.justindevb.replay.util.io.ItemStackSerializer.serializeItem;

/**
 * Collects per-tick snapshots into the final timeline data structure.
 */
public class TimelineBuilder {

    private final List<TimelineEvent> timeline;
    private final ReplayAppendLogWriter appendLogWriter;

    public TimelineBuilder() {
        this(null, true);
    }

    public TimelineBuilder(ReplayAppendLogWriter appendLogWriter, boolean retainTimeline) {
        this.timeline = retainTimeline ? new ArrayList<>() : null;
        this.appendLogWriter = appendLogWriter;
    }

    public void addEvent(TimelineEvent event) {
        if (timeline != null) {
            timeline.add(event);
        }
        if (appendLogWriter != null) {
            try {
                appendLogWriter.append(event);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to append timeline event to recording temp log", e);
            }
        }
    }

    public List<TimelineEvent> getTimeline() {
        return timeline != null ? timeline : List.of();
    }

    /**
     * Capture a player's full inventory into a typed timeline event.
     */
    public TimelineEvent.InventoryUpdate captureInventory(int tick, String uuid, org.bukkit.entity.Player p) {
        List<String> armor = new ArrayList<>(4);
        armor.add(serializeItem(p.getInventory().getBoots()));
        armor.add(serializeItem(p.getInventory().getLeggings()));
        armor.add(serializeItem(p.getInventory().getChestplate()));
        armor.add(serializeItem(p.getInventory().getHelmet()));

        List<String> contents = new ArrayList<>();
        for (ItemStack item : p.getInventory().getContents()) {
            contents.add(serializeItem(item));
        }

        return new TimelineEvent.InventoryUpdate(
                tick, uuid,
                serializeItem(p.getInventory().getItemInMainHand()),
                serializeItem(p.getInventory().getItemInOffHand()),
                armor, contents
        );
    }
}
