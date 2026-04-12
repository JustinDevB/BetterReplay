package me.justindevb.replay.playback;

import me.justindevb.replay.entity.RecordedEntity;
import me.justindevb.replay.entity.RecordedPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Manages viewer inventory save/restore, replay control items, player menu,
 * and inventory-related event handlers during replay playback.
 */
public class ReplayInventoryUI implements Listener {

    /**
     * Callback interface for actions that must be delegated back to ReplaySession.
     */
    public interface SessionControl {
        void togglePause();
        void skipSeconds(int seconds);
        void stop();
        boolean isActive();
    }

    private final Player viewer;
    private final Supplier<Map<UUID, RecordedEntity>> recordedEntitiesSupplier;
    private final SessionControl sessionControl;

    private ItemStack[] viewerInventory;
    private ItemStack[] viewerArmor;
    private ItemStack viewerOffHand;

    public ReplayInventoryUI(Player viewer,
                             Supplier<Map<UUID, RecordedEntity>> recordedEntitiesSupplier,
                             SessionControl sessionControl) {
        this.viewer = viewer;
        this.recordedEntitiesSupplier = recordedEntitiesSupplier;
        this.sessionControl = sessionControl;
    }

    public void copyInventory() {
        this.viewerInventory = viewer.getInventory().getContents().clone();
        this.viewerArmor = viewer.getInventory().getArmorContents().clone();
        this.viewerOffHand = viewer.getInventory().getItemInOffHand().clone();
        viewer.getInventory().clear();
    }

    public void restoreInventory() {
        viewer.getInventory().clear();
        viewer.getInventory().setContents(viewerInventory);
        viewer.getInventory().setArmorContents(viewerArmor);
        viewer.getInventory().setItemInOffHand(viewerOffHand);
        viewer.updateInventory();
    }

    public void giveReplayControls() {
        ItemStack pauseButton = new ItemStack(Material.RED_DYE);
        ItemMeta pauseMeta = pauseButton.getItemMeta();
        pauseMeta.displayName(Component.text("Pause / Play", NamedTextColor.RED));
        pauseButton.setItemMeta(pauseMeta);

        ItemStack skipForward = new ItemStack(Material.LIME_DYE);
        ItemMeta forwardMeta = skipForward.getItemMeta();
        forwardMeta.displayName(Component.text("+5 seconds", NamedTextColor.GREEN));
        skipForward.setItemMeta(forwardMeta);

        ItemStack skipBackward = new ItemStack(Material.YELLOW_DYE);
        ItemMeta backwardMeta = skipBackward.getItemMeta();
        backwardMeta.displayName(Component.text("-5 seconds", NamedTextColor.YELLOW));
        skipBackward.setItemMeta(backwardMeta);

        ItemStack stopReplay = new ItemStack(Material.BARRIER);
        ItemMeta stopMeta = stopReplay.getItemMeta();
        stopMeta.displayName(Component.text("Exit Replay", NamedTextColor.DARK_RED));
        stopReplay.setItemMeta(stopMeta);

        ItemStack playerMenu = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta menuMeta = playerMenu.getItemMeta();
        menuMeta.displayName(Component.text("Players", NamedTextColor.AQUA));
        playerMenu.setItemMeta(menuMeta);

        viewer.getInventory().setItem(4, pauseButton);
        viewer.getInventory().setItem(5, skipForward);
        viewer.getInventory().setItem(3, skipBackward);
        viewer.getInventory().setItem(6, playerMenu);
        viewer.getInventory().setItem(8, stopReplay);

        viewer.getInventory().setHeldItemSlot(4);
    }

    public void openPlayerMenu() {
        Inventory inv = Bukkit.createInventory(
                null,
                27,
                Component.text("Recorded Players", NamedTextColor.DARK_GRAY)
        );

        for (RecordedEntity entity : recordedEntitiesSupplier.get().values()) {
            if (!(entity instanceof RecordedPlayer rp))
                continue;

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(rp.getUuid()));
            meta.displayName(Component.text(rp.getName(), NamedTextColor.YELLOW));
            head.setItemMeta(meta);

            inv.addItem(head);
        }

        viewer.openInventory(inv);
    }

    /**
     * Return the RecordedPlayer the viewer is aiming at, or null if none.
     * Uses AABB ray-box intersection against the player hitbox (0.6 wide x 1.8 tall).
     */
    public RecordedPlayer getTargetedRecordedPlayer(Player player) {
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector origin = eye.toVector();
        org.bukkit.util.Vector dir = eye.getDirection().normalize();

        final double halfW = 0.3;
        final double height = 1.8;

        RecordedPlayer closest = null;
        double closestDist = Double.MAX_VALUE;

        for (RecordedEntity re : recordedEntitiesSupplier.get().values()) {
            if (!(re instanceof RecordedPlayer rp)) continue;

            Location loc = re.getCurrentLocation();
            if (loc == null || !eye.getWorld().equals(loc.getWorld()))
                continue;

            double dx = loc.getX() - origin.getX();
            double dy = loc.getY() - origin.getY();
            double dz = loc.getZ() - origin.getZ();
            if (dx * dx + dy * dy + dz * dz > 400.0) continue;

            double minX = loc.getX() - halfW - origin.getX();
            double maxX = loc.getX() + halfW - origin.getX();
            double minY = loc.getY() - origin.getY();
            double maxY = loc.getY() + height - origin.getY();
            double minZ = loc.getZ() - halfW - origin.getZ();
            double maxZ = loc.getZ() + halfW - origin.getZ();

            double tMin = Double.NEGATIVE_INFINITY;
            double tMax = Double.POSITIVE_INFINITY;

            // X slab
            if (Math.abs(dir.getX()) < 1e-9) {
                if (minX > 0 || maxX < 0) continue;
            } else {
                double invD = 1.0 / dir.getX();
                double t1 = minX * invD;
                double t2 = maxX * invD;
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) continue;
            }

            // Y slab
            if (Math.abs(dir.getY()) < 1e-9) {
                if (minY > 0 || maxY < 0) continue;
            } else {
                double invD = 1.0 / dir.getY();
                double t1 = minY * invD;
                double t2 = maxY * invD;
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) continue;
            }

            // Z slab
            if (Math.abs(dir.getZ()) < 1e-9) {
                if (minZ > 0 || maxZ < 0) continue;
            } else {
                double invD = 1.0 / dir.getZ();
                double t1 = minZ * invD;
                double t2 = maxZ * invD;
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) continue;
            }

            if (tMax < 0) continue;
            double hitDist = tMin >= 0 ? tMin : tMax;
            if (hitDist < closestDist) {
                closest = rp;
                closestDist = hitDist;
            }
        }
        return closest;
    }

    // -- Event Handlers --

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        Player player = e.getPlayer();
        if (!player.equals(this.viewer))
            return;

        ItemStack handItem = e.getItem();
        if (handItem == null || !handItem.hasItemMeta())
            return;

        Component displayName = handItem.getItemMeta().displayName();
        String name = displayName instanceof TextComponent tc ? tc.content() : "";

        RecordedPlayer targetPlayer = getTargetedRecordedPlayer(player);
        if (targetPlayer != null) {
            targetPlayer.openInventoryForViewer(player);
            e.setCancelled(true);
            return;
        }

        switch (name) {
            case "Pause / Play" -> sessionControl.togglePause();
            case "+5 seconds" -> sessionControl.skipSeconds(5);
            case "-5 seconds" -> sessionControl.skipSeconds(-5);
            case "Exit Replay" -> sessionControl.stop();
            case "Players" -> openPlayerMenu();
        }

        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent e) {
        Player player = (Player) e.getWhoClicked();
        if (!player.equals(viewer))
            return;

        if (!sessionControl.isActive())
            return;

        e.setCancelled(true);
    }

    @EventHandler
    public void onPlayerMenuClick(InventoryClickEvent e) {
        Component title = e.getView().title();
        if (!(title instanceof TextComponent tc) || !tc.content().equals("Recorded Players"))
            return;

        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player player))
            return;

        ItemStack item = e.getCurrentItem();
        if (item == null || !(item.getItemMeta() instanceof SkullMeta meta))
            return;

        OfflinePlayer target = meta.getOwningPlayer();
        if (target == null)
            return;

        RecordedEntity recorded = recordedEntitiesSupplier.get().get(target.getUniqueId());
        if (recorded == null)
            return;

        // Teleport needed - use the replay's FoliaLib but accessed via the supplier pattern
        // The teleportAsync call requires FoliaLib which is on the Replay instance.
        // We'll rely on the caller providing this capability or use Bukkit's built-in teleport.
        player.teleport(recorded.getCurrentLocation());
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() != viewer)
            return;
        if (!sessionControl.isActive())
            return;
        Component dragTitle = e.getView().title();
        if (!(dragTitle instanceof TextComponent dtc) || !dtc.content().contains("'s Inventory"))
            return;

        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        Player player = e.getPlayer();

        if (!sessionControl.isActive())
            return;

        if (!player.equals(viewer))
            return;

        ItemStack item = e.getItemDrop().getItemStack();
        if (item == null || !item.hasItemMeta())
            return;

        Component dropDisplayName = item.getItemMeta().displayName();
        String dropName = dropDisplayName instanceof TextComponent tc ? tc.content() : "";
        if (dropName.equals("Pause / Play") || dropName.equals("+5 seconds") || dropName.equals("-5 seconds")) {
            e.setCancelled(true);
        }
    }
}
