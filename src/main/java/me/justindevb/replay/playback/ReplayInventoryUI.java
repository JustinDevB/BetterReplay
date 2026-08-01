package me.justindevb.replay.playback;

import me.justindevb.replay.Replay;
import me.justindevb.replay.config.ReplayMessagesConfig;
import me.justindevb.replay.entity.RecordedEntity;
import me.justindevb.replay.entity.RecordedPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Manages viewer inventory save/restore, replay control items, player menu,
 * and inventory-related event handlers during replay playback.
 */
public class ReplayInventoryUI implements Listener {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /**
     * Callback interface for actions that must be delegated back to ReplaySession.
     */
    public interface SessionControl {
        void togglePause();
        void skipSeconds(int seconds);
        void stepTick(int direction);
        void changeSpeed(int direction);
        void stop();
        boolean isActive();
    }

    private final Player viewer;
    private final Replay replay;
    private final NamespacedKey controlKey;
    private final Supplier<Map<UUID, RecordedEntity>> recordedEntitiesSupplier;
    private final SessionControl sessionControl;

    private ItemStack[] viewerInventory;
    private ItemStack[] viewerArmor;
    private ItemStack viewerOffHand;

    public ReplayInventoryUI(Replay replay,
                             Player viewer,
                             Supplier<Map<UUID, RecordedEntity>> recordedEntitiesSupplier,
                             SessionControl sessionControl) {
        this.replay = replay;
        this.controlKey = new NamespacedKey("betterreplay", "replay-control");
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

    /**
     * Transfers the saved (original) inventory from another session's inventory UI,
     * so that the original items are preserved across nested replay sessions.
     */
    public void transferSavedInventory(ReplayInventoryUI other) {
        this.viewerInventory = other.viewerInventory;
        this.viewerArmor = other.viewerArmor;
        this.viewerOffHand = other.viewerOffHand;
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
        pauseMeta.displayName(message("items.pause-play", "<red>Pause / Play"));
        pauseButton.setItemMeta(pauseMeta);
        markControl(pauseButton, "pause-play");

        ItemStack skipForward = new ItemStack(Material.LIME_DYE);
        ItemMeta forwardMeta = skipForward.getItemMeta();
        forwardMeta.displayName(message("items.skip-forward", "<green>+5 seconds"));
        skipForward.setItemMeta(forwardMeta);
        markControl(skipForward, "skip-forward");

        ItemStack skipBackward = new ItemStack(Material.YELLOW_DYE);
        ItemMeta backwardMeta = skipBackward.getItemMeta();
        backwardMeta.displayName(message("items.skip-backward", "<yellow>-5 seconds"));
        skipBackward.setItemMeta(backwardMeta);
        markControl(skipBackward, "skip-backward");

        ItemStack stopReplay = new ItemStack(Material.BARRIER);
        ItemMeta stopMeta = stopReplay.getItemMeta();
        stopMeta.displayName(message("items.exit", "<dark_red>Exit Replay"));
        stopReplay.setItemMeta(stopMeta);
        markControl(stopReplay, "exit");

        ItemStack playerMenu = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta menuMeta = playerMenu.getItemMeta();
        menuMeta.displayName(message("items.players", "<aqua>Players"));
        playerMenu.setItemMeta(menuMeta);
        markControl(playerMenu, "players");

        viewer.getInventory().setItem(0, skipBackward);
        viewer.getInventory().setItem(1, pauseButton);
        viewer.getInventory().setItem(2, skipForward);
        viewer.getInventory().setItem(3, playerMenu);
        viewer.getInventory().setItem(8, stopReplay);

        viewer.getInventory().setHeldItemSlot(1);
    }

    public void showStepControls() {
        ItemStack stepBack = new ItemStack(Material.CYAN_DYE);
        ItemMeta backMeta = stepBack.getItemMeta();
        backMeta.displayName(message("items.previous-frame", "<aqua>\u25C0\u25C0 Previous Frame"));
        stepBack.setItemMeta(backMeta);
        markControl(stepBack, "previous-frame");

        ItemStack stepForward = new ItemStack(Material.MAGENTA_DYE);
        ItemMeta fwdMeta = stepForward.getItemMeta();
        fwdMeta.displayName(message("items.next-frame", "<light_purple>\u25B6\u25B6 Next Frame"));
        stepForward.setItemMeta(fwdMeta);
        markControl(stepForward, "next-frame");

        viewer.getInventory().setItem(5, stepBack);
        viewer.getInventory().setItem(6, stepForward);
    }

    public void hideStepControls() {
        viewer.getInventory().setItem(5, null);
        viewer.getInventory().setItem(6, null);
    }

    public void showSpeedControls(double currentSpeed) {
        String speedText = String.format("%.1fx", currentSpeed);
        List<Component> speedLore = List.of(message("items.speed-lore", "<gray>Current: %speed%", "speed", speedText));

        ItemStack slower = new ItemStack(Material.ORANGE_DYE);
        ItemMeta slowerMeta = slower.getItemMeta();
        slowerMeta.displayName(message("items.slower", "<gold>\u23EA Slower"));
        slowerMeta.lore(speedLore);
        slower.setItemMeta(slowerMeta);
        markControl(slower, "slower");

        ItemStack faster = new ItemStack(Material.LIGHT_BLUE_DYE);
        ItemMeta fasterMeta = faster.getItemMeta();
        fasterMeta.displayName(message("items.faster", "<blue>\u23E9 Faster"));
        fasterMeta.lore(speedLore);
        faster.setItemMeta(fasterMeta);
        markControl(faster, "faster");

        viewer.getInventory().setItem(5, slower);
        viewer.getInventory().setItem(6, faster);
    }

    public void openPlayerMenu() {
        Inventory inv = Bukkit.createInventory(
                null,
                27,
                message("menus.recorded-players", "<dark_gray>Recorded Players")
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

        if (!sessionControl.isActive())
            return;

        ItemStack handItem = e.getItem();
        if (handItem == null || !handItem.hasItemMeta())
            return;

        String control = getControl(handItem);

        RecordedPlayer targetPlayer = getTargetedRecordedPlayer(player);
        if (targetPlayer != null) {
            targetPlayer.openInventoryForViewer(player);
            e.setCancelled(true);
            return;
        }

        if (control == null) return;

        switch (control) {
            case "pause-play" -> sessionControl.togglePause();
            case "skip-forward" -> sessionControl.skipSeconds(5);
            case "skip-backward" -> sessionControl.skipSeconds(-5);
            case "previous-frame" -> sessionControl.stepTick(-1);
            case "next-frame" -> sessionControl.stepTick(1);
            case "slower" -> sessionControl.changeSpeed(-1);
            case "faster" -> sessionControl.changeSpeed(1);
            case "exit" -> sessionControl.stop();
            case "players" -> openPlayerMenu();
            default -> { return; }
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

        Location targetLocation = recorded.getCurrentLocation();
        if (targetLocation == null || targetLocation.getWorld() == null)
            return;

        Location teleportLocation = targetLocation.clone();
        if (teleportLocation == null)
            return;

        replay.getFoliaLib().getScheduler().teleportAsync(player, teleportLocation);
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

        if (getControl(item) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityPickupItem(EntityPickupItemEvent e) {
        if (!sessionControl.isActive())
            return;

        if (!viewer.equals(e.getEntity()))
            return;

        e.setCancelled(true);
    }

    private Component message(String key, String fallback, String... replacements) {
        ReplayMessagesConfig messages = replay.getMessages();
        if (messages != null) return messages.component(key, fallback, replacements);
        String value = fallback;
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            value = value.replace("%" + replacements[index] + "%", replacements[index + 1]);
        }
        return MINI_MESSAGE.deserialize(value);
    }

    private void markControl(ItemStack item, String control) {
        item.editPersistentDataContainer(pdc -> pdc.set(controlKey, PersistentDataType.STRING, control));
    }

    private String getControl(ItemStack item) {
        return item.getPersistentDataContainer().get(controlKey, PersistentDataType.STRING);
    }
}
