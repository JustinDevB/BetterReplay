package me.justindevb.replay.recording.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

class InventoryCaptureMemoTest {

    private static ItemStack stack(byte marker) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.STONE);
        when(item.getAmount()).thenReturn(1);
        when(item.serializeAsBytes()).thenReturn(new byte[] {marker});
        when(item.clone()).thenReturn(item);
        return item;
    }

    private static Player player(PlayerInventory inventory) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getInventory()).thenReturn(inventory);
        return player;
    }

    @Test
    void unchangedSlotIsSerializedOnce() {
        ItemStack item = stack((byte) 1);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[] {item});
        Player player = player(inventory);
        InventoryCaptureService service = new InventoryCaptureService();

        var first = service.captureStorage(player);
        var second = service.captureStorage(player);

        assertEquals(first.snapshotHash(), second.snapshotHash());
        verify(item, times(1)).serializeAsBytes();
    }

    @Test
    void changedSlotIsSerializedAgain() {
        ItemStack before = stack((byte) 1);
        ItemStack after = stack((byte) 2);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getStorageContents())
                .thenReturn(new ItemStack[] {before})
                .thenReturn(new ItemStack[] {after});
        Player player = player(inventory);
        InventoryCaptureService service = new InventoryCaptureService();

        var first = service.captureStorage(player);
        var second = service.captureStorage(player);

        assertNotEquals(first.snapshotHash(), second.snapshotHash());
    }

    @Test
    void emptiedSlotIsNotLeftOnTheOldItem() {
        ItemStack item = stack((byte) 1);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getStorageContents())
                .thenReturn(new ItemStack[] {item})
                .thenReturn(new ItemStack[] {null});
        Player player = player(inventory);
        InventoryCaptureService service = new InventoryCaptureService();

        var first = service.captureStorage(player);
        var second = service.captureStorage(player);

        assertNotEquals(first.snapshotHash(), second.snapshotHash());
    }
}
