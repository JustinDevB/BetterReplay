package me.justindevb.replay.recording.inventory;

import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.util.io.SerializedItemData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InventoryCaptureService {

    private static final int EQUIPMENT_SLOTS = 6;

    // Last serialized form per slot. Serializing gzips the item NBT, so unchanged slots reuse it.
    private final Map<UUID, ItemMemo> storageMemos = new ConcurrentHashMap<>();
    private final Map<UUID, ItemMemo> equipmentMemos = new ConcurrentHashMap<>();

    public CapturedInventoryStorageSnapshot captureStorage(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storageContents = inventory.getStorageContents();
        if (storageContents == null) {
            storageContents = new ItemStack[36];
        }
        ItemMemo memo = storageMemos.computeIfAbsent(player.getUniqueId(), ignored -> new ItemMemo());
        memo.ensureSize(storageContents.length);
        List<SerializedItemData> captured = new ArrayList<>(storageContents.length);
        for (int slot = 0; slot < storageContents.length; slot++) {
            captured.add(memo.serialize(slot, storageContents[slot]));
        }
        List<SerializedItemData> storage = List.copyOf(captured);
        return new CapturedInventoryStorageSnapshot(storage, computeHash(storage));
    }

    public CapturedInventoryStorageSnapshot captureStorage(PlayerInventory inventory) {
        ItemStack[] storageContents = inventory.getStorageContents();
        if (storageContents == null) {
            storageContents = new ItemStack[36];
        }
        List<SerializedItemData> storage = captureItems(storageContents);
        return new CapturedInventoryStorageSnapshot(storage, computeHash(storage));
    }

    public CapturedEquipmentState captureEquipment(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemMemo memo = equipmentMemos.computeIfAbsent(player.getUniqueId(), ignored -> new ItemMemo());
        memo.ensureSize(EQUIPMENT_SLOTS);
        List<SerializedItemData> armor = List.of(
                memo.serialize(0, inventory.getBoots()),
                memo.serialize(1, inventory.getLeggings()),
                memo.serialize(2, inventory.getChestplate()),
                memo.serialize(3, inventory.getHelmet())
        );
        SerializedItemData mainHand = memo.serialize(4, inventory.getItemInMainHand());
        SerializedItemData offHand = memo.serialize(5, inventory.getItemInOffHand());
        long stateHash = computeHash(armor, inventory.getHeldItemSlot(), mainHand, offHand);
        return new CapturedEquipmentState(inventory.getHeldItemSlot(), mainHand, offHand, armor, stateHash);
    }

    public CapturedEquipmentState captureEquipment(PlayerInventory inventory) {
        List<SerializedItemData> armor = List.of(
                SerializedItemData.fromItemStack(inventory.getBoots()),
                SerializedItemData.fromItemStack(inventory.getLeggings()),
                SerializedItemData.fromItemStack(inventory.getChestplate()),
                SerializedItemData.fromItemStack(inventory.getHelmet())
        );
        SerializedItemData mainHand = SerializedItemData.fromItemStack(inventory.getItemInMainHand());
        SerializedItemData offHand = SerializedItemData.fromItemStack(inventory.getItemInOffHand());
        long stateHash = computeHash(armor, inventory.getHeldItemSlot(), mainHand, offHand);
        return new CapturedEquipmentState(inventory.getHeldItemSlot(), mainHand, offHand, armor, stateHash);
    }

    public boolean hasStorageChanged(CapturedInventoryStorageSnapshot current, CapturedInventoryStorageSnapshot previous) {
        if (previous == null) {
            return true;
        }
        if (current.snapshotHash() != previous.snapshotHash()) {
            return true;
        }
        return !current.storage().equals(previous.storage());
    }

    public boolean hasEquipmentChanged(CapturedEquipmentState current, CapturedEquipmentState previous) {
        if (previous == null) {
            return true;
        }
        if (current.heldSlot() != previous.heldSlot()) {
            return true;
        }
        if (current.stateHash() != previous.stateHash()) {
            return true;
        }
        if (!current.mainHand().equals(previous.mainHand())) {
            return true;
        }
        if (!current.offHand().equals(previous.offHand())) {
            return true;
        }
        return !current.armor().equals(previous.armor());
    }

    public TimelineEvent.InventoryStorageUpdate toStorageEvent(int tick, String uuid, CapturedInventoryStorageSnapshot snapshot) {
        return new TimelineEvent.InventoryStorageUpdate(tick, uuid, snapshot.storage());
    }

    public TimelineEvent.EquipmentStateUpdate toEquipmentEvent(int tick, String uuid, CapturedEquipmentState state) {
        return new TimelineEvent.EquipmentStateUpdate(tick, uuid, state.heldSlot(), state.mainHand(), state.offHand(), state.armor());
    }

    private static List<SerializedItemData> captureItems(ItemStack[] items) {
        if (items == null) {
            return List.of();
        }
        List<SerializedItemData> captured = new ArrayList<>(items.length);
        for (ItemStack item : items) {
            captured.add(SerializedItemData.fromItemStack(item));
        }
        return List.copyOf(captured);
    }

    private static long computeHash(List<SerializedItemData> items) {
        long hash = 1L;
        for (SerializedItemData item : items) {
            hash = 31L * hash + item.hashCode();
        }
        return hash;
    }

    private static long computeHash(List<SerializedItemData> armor, int heldSlot, SerializedItemData mainHand, SerializedItemData offHand) {
        long hash = computeHash(armor);
        hash = 31L * hash + heldSlot;
        hash = 31L * hash + mainHand.hashCode();
        hash = 31L * hash + offHand.hashCode();
        return hash;
    }

    private static final class ItemMemo {
        private ItemStack[] items = new ItemStack[0];
        private SerializedItemData[] serialized = new SerializedItemData[0];

        void ensureSize(int size) {
            if (items.length != size) {
                items = new ItemStack[size];
                serialized = new SerializedItemData[size];
            }
        }

        SerializedItemData serialize(int slot, ItemStack item) {
            if (item == null) {
                items[slot] = null;
                serialized[slot] = null;
                return SerializedItemData.fromItemStack(null);
            }
            if (serialized[slot] != null && item.equals(items[slot])) {
                return serialized[slot];
            }
            SerializedItemData data = SerializedItemData.fromItemStack(item);
            // The inventory hands out live stacks, so keep a copy to compare against.
            items[slot] = item.clone();
            serialized[slot] = data;
            return data;
        }
    }

    public record CapturedInventoryStorageSnapshot(List<SerializedItemData> storage, long snapshotHash) {
        public CapturedInventoryStorageSnapshot {
            storage = List.copyOf(storage);
        }
    }

    public record CapturedEquipmentState(
            int heldSlot,
            SerializedItemData mainHand,
            SerializedItemData offHand,
            List<SerializedItemData> armor,
            long stateHash
    ) {
        public CapturedEquipmentState {
            armor = List.copyOf(armor);
        }
    }
}