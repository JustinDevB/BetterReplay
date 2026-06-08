package me.justindevb.replay.playback;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import me.justindevb.replay.Replay;
import me.justindevb.replay.entity.RecordedEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.mockito.Mockito.*;

/**
 * Tests for {@link ReplayInventoryUI} inventory save/restore and transfer logic.
 */
@ExtendWith(MockitoExtension.class)
class ReplayInventoryUITest {

    @Mock private Replay replay;
    @Mock private FoliaLib foliaLib;
    @Mock private PlatformScheduler scheduler;
    @Mock private Player viewer;
    @Mock private Player targetViewer;
    @Mock private PlayerInventory playerInventory;
    @Mock private InventoryClickEvent clickEvent;
    @Mock private InventoryView inventoryView;
    @Mock private ItemStack clickedItem;
    @Mock private SkullMeta skullMeta;
    @Mock private org.bukkit.OfflinePlayer offlinePlayer;
    @Mock private RecordedEntity recordedEntity;
    @Mock private org.bukkit.Location targetLocation;
    @Mock private org.bukkit.Location targetLocationClone;

    private final Supplier<Map<UUID, RecordedEntity>> emptyEntities = Collections::emptyMap;
    private final ReplayInventoryUI.SessionControl noOpControl = new ReplayInventoryUI.SessionControl() {
        @Override public void togglePause() {}
        @Override public void skipSeconds(int seconds) {}
        @Override public void stepTick(int direction) {}
        @Override public void changeSpeed(int direction) {}
        @Override public void stop() {}
        @Override public boolean isActive() { return true; }
    };

    @BeforeEach
    void setUp() {
        lenient().when(viewer.getInventory()).thenReturn(playerInventory);
        lenient().when(replay.getFoliaLib()).thenReturn(foliaLib);
        lenient().when(foliaLib.getScheduler()).thenReturn(scheduler);
    }

    @Test
    void copyInventory_savesAndClearsPlayerInventory() {
        ItemStack[] contents = new ItemStack[36];
        ItemStack[] armor = new ItemStack[4];
        ItemStack offHand = mock(ItemStack.class);

        when(playerInventory.getContents()).thenReturn(contents);
        when(playerInventory.getArmorContents()).thenReturn(armor);
        when(playerInventory.getItemInOffHand()).thenReturn(offHand);

        ReplayInventoryUI ui = new ReplayInventoryUI(replay, viewer, emptyEntities, noOpControl);
        ui.copyInventory();

        verify(playerInventory).clear();
    }

    @Test
    void restoreInventory_restoresSavedState() {
        ItemStack[] contents = new ItemStack[36];
        ItemStack[] armor = new ItemStack[4];
        ItemStack offHand = mock(ItemStack.class);
        when(offHand.clone()).thenReturn(offHand);

        when(playerInventory.getContents()).thenReturn(contents);
        when(playerInventory.getArmorContents()).thenReturn(armor);
        when(playerInventory.getItemInOffHand()).thenReturn(offHand);

        ReplayInventoryUI ui = new ReplayInventoryUI(replay, viewer, emptyEntities, noOpControl);
        ui.copyInventory();

        // Reset so we can verify restore calls
        reset(playerInventory);
        when(viewer.getInventory()).thenReturn(playerInventory);

        ui.restoreInventory();

        verify(playerInventory).clear();
        verify(playerInventory).setContents(any(ItemStack[].class));
        verify(playerInventory).setArmorContents(any(ItemStack[].class));
        verify(playerInventory).setItemInOffHand(offHand);
        verify(viewer).updateInventory();
    }

    @Test
    void transferSavedInventory_nestedReplayPreservesOriginal() {
        // Simulate original inventory
        ItemStack[] originalContents = new ItemStack[36];
        ItemStack[] originalArmor = new ItemStack[4];
        ItemStack originalOffHand = mock(ItemStack.class);
        when(originalOffHand.clone()).thenReturn(originalOffHand);

        when(playerInventory.getContents()).thenReturn(originalContents);
        when(playerInventory.getArmorContents()).thenReturn(originalArmor);
        when(playerInventory.getItemInOffHand()).thenReturn(originalOffHand);

        // Session 1: saves original inventory
        ReplayInventoryUI ui1 = new ReplayInventoryUI(replay, viewer, emptyEntities, noOpControl);
        ui1.copyInventory();

        // Session 2: transfers from session 1 instead of copying current (contaminated) inventory
        ReplayInventoryUI ui2 = new ReplayInventoryUI(replay, viewer, emptyEntities, noOpControl);
        ui2.transferSavedInventory(ui1);

        // Reset mocks to verify restore
        reset(playerInventory);
        when(viewer.getInventory()).thenReturn(playerInventory);

        // Session 2 restores -> should restore original inventory, not replay controls
        ui2.restoreInventory();

        verify(playerInventory).setItemInOffHand(originalOffHand);
    }

    @Test
    void onEntityPickupItem_activeViewerCancelsPickup() {
        EntityPickupItemEvent event = mock(EntityPickupItemEvent.class);
        when(event.getEntity()).thenReturn(viewer);

        ReplayInventoryUI ui = new ReplayInventoryUI(replay, viewer, emptyEntities, noOpControl);

        ui.onEntityPickupItem(event);

        verify(event).setCancelled(true);
    }

    @Test
    void onEntityPickupItem_inactiveSessionLeavesPickupUnchanged() {
        EntityPickupItemEvent event = mock(EntityPickupItemEvent.class);

        ReplayInventoryUI ui = new ReplayInventoryUI(replay, viewer, emptyEntities, sessionControl(false));

        ui.onEntityPickupItem(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void onPlayerMenuClick_teleportsViewerThroughFoliaScheduler() {
        UUID targetId = UUID.randomUUID();
        org.bukkit.Location targetLocation = mock(org.bukkit.Location.class);
        Map<UUID, RecordedEntity> entities = Map.of(targetId, recordedEntity);

        when(clickEvent.getView()).thenReturn(inventoryView);
        when(inventoryView.title()).thenReturn(net.kyori.adventure.text.Component.text("Recorded Players"));
        when(clickEvent.getWhoClicked()).thenReturn(viewer);
        when(clickEvent.getCurrentItem()).thenReturn(clickedItem);
        when(clickedItem.getItemMeta()).thenReturn(skullMeta);
        when(skullMeta.getOwningPlayer()).thenReturn(offlinePlayer);
        when(offlinePlayer.getUniqueId()).thenReturn(targetId);
        when(recordedEntity.getCurrentLocation()).thenReturn(targetLocation);
        when(targetLocation.getWorld()).thenReturn(mock(org.bukkit.World.class));
        when(targetLocation.clone()).thenReturn(targetLocationClone);

        ReplayInventoryUI ui = new ReplayInventoryUI(replay, viewer, () -> entities, noOpControl);

        ui.onPlayerMenuClick(clickEvent);

        verify(scheduler).teleportAsync(viewer, targetLocationClone);
    }

    private ReplayInventoryUI.SessionControl sessionControl(boolean active) {
        return new ReplayInventoryUI.SessionControl() {
            @Override public void togglePause() {}
            @Override public void skipSeconds(int seconds) {}
            @Override public void stepTick(int direction) {}
            @Override public void changeSpeed(int direction) {}
            @Override public void stop() {}
            @Override public boolean isActive() { return active; }
        };
    }
}
