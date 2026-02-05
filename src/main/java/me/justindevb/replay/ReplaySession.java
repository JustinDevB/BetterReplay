package me.justindevb.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.google.gson.Gson;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import me.justindevb.replay.api.events.ReplayStopEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class ReplaySession implements Listener, PacketListener {
    //private final File file;
    private final Player viewer;
    private final Replay replay;
    private final Gson gson = new Gson();

    private int replayTaskId = -1;

    private List<Map<String, Object>> timeline;
    //private List<Integer> trackedEntityIds = new ArrayList<>();
    private final Set<Integer> trackedEntityIds = new HashSet<>();
    private final Map<UUID, RecordedEntity> recordedEntities = new HashMap<>();
    private int tick = 0;
    private boolean paused = false;
    private ItemStack[] viewerInventory;
    private ItemStack[] viewerArmor;
    private ItemStack viewerOffHand;

    public ReplaySession(List<Map<String, Object>> timeline, Player viewer, Replay replay) {
        this.timeline = timeline;
        this.viewer = viewer;
        this.replay = replay;
        Bukkit.getPluginManager().registerEvents(this, replay);
    }

    public void start() {
        if (timeline == null || timeline.isEmpty()) {
            viewer.sendMessage("Replay is empty!");
            return;
        }

        ReplayRegistry.add(this);
        copyInventory();

        Map<String, Object> firstLocationEvent = timeline.stream()
                .filter(e -> e.containsKey("x") && e.containsKey("y") && e.containsKey("z"))
                .findFirst()
                .orElse(null);

        if (firstLocationEvent != null) {
            Double x = asDouble(firstLocationEvent.get("x"));
            Double y = asDouble(firstLocationEvent.get("y"));
            Double z = asDouble(firstLocationEvent.get("z"));
            Float yaw = asFloat(firstLocationEvent.get("yaw"));
            Float pitch = asFloat(firstLocationEvent.get("pitch"));

            if (x != null && y != null && z != null) {
                viewer.teleport(new Location(viewer.getWorld(), x, y, z, yaw, pitch));
            }
        }

        giveReplayControls(viewer);

     //   Bukkit.getPluginManager().callEvent(new ReplayStartEvent(viewer, file, this));

        new BukkitRunnable() {
            @Override
            public void run() {
                replayTaskId = getTaskId();

                if (tick >= timeline.size()) {
                    cancel();
                    stop();
                    return;
                }

                if (viewer == null || !viewer.isOnline()) {
                    cancel();
                    recordedEntities.values().forEach(RecordedEntity::destroy);
                    recordedEntities.clear();
                    return;
                }

                Map<String, Object> firstEvent = timeline.get(tick);
                Object tickObj = firstEvent.get("tick");

                if (!(tickObj instanceof Number)) {
                    tick++;
                    return;
                }

                int recordedTick = ((Number) tickObj).intValue();

                while (tick < timeline.size()) {
                    Map<String, Object> event = timeline.get(tick);

                    Object eventTickObj = event.get("tick");
                    if (!(eventTickObj instanceof Number)) break;

                    int eventTick = ((Number) eventTickObj).intValue();
                    if (eventTick != recordedTick) break;

                    String type = (String) event.get("type");
                    if (type == null) {
                        tick++;
                        continue;
                    }

                    Object uuidObj = event.get("uuid");
                    if (!(uuidObj instanceof String uuidStr)) {
                        tick++;
                        continue;
                    }

                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidStr);
                    } catch (IllegalArgumentException ex) {
                        tick++;
                        continue;
                    }

                    if ("player_quit".equals(type)) {
                        RecordedEntity entity = recordedEntities.remove(uuid);
                        if (entity != null) {
                            entity.destroy();
                            trackedEntityIds.remove(entity.getFakeEntityId());
                        }
                        tick++;
                        continue;
                    }

                    RecordedEntity recorded = recordedEntities.get(uuid);

                    if (recorded != null && recorded.isDestroyed()) {
                        recordedEntities.remove(uuid);
                        tick++;
                        continue;
                    }

                    if (recorded == null) {
                        Double x = asDouble(event.get("x"));
                        Double y = asDouble(event.get("y"));
                        Double z = asDouble(event.get("z"));

                        if (x == null || y == null || z == null) {
                            tick++;
                            continue;
                        }

                        Location initialLoc = new Location(
                                viewer.getWorld(),
                                x,
                                y,
                                z,
                                asFloat(event.get("yaw")),
                                asFloat(event.get("pitch"))
                        );

                        recorded = RecordedEntityFactory.create(event, viewer);
                        if (recorded == null) {
                            tick++;
                            continue;
                        }

                        recorded.spawn(initialLoc);
                        recordedEntities.put(uuid, recorded);

                        if (recorded instanceof RecordedPlayer rp) {
                            Map<String, Object> inv = getInventorySnapshotForPlayer(uuid);
                            if (inv != null) rp.updateInventory(inv);
                        }
                    }

                    handleEvent(recorded, event);

                    if ("entity_death".equals(type)) {
                        recorded.destroy();
                        recordedEntities.remove(uuid);
                    }

                    tick++;
                }
                sendActionBar();
            }
        }.runTaskTimer(replay, 1L, 1L);
    }


    public void stop() {
        viewer.sendActionBar(Component.empty());

        Bukkit.getPluginManager().callEvent(new ReplayStopEvent(viewer, this));
        recordedEntities.values().forEach(RecordedEntity::destroy);
        recordedEntities.clear();

        clearFakeItems();
        restoreInventory();
        if (replayTaskId != -1) {
            Bukkit.getScheduler().cancelTask(replayTaskId);
            replayTaskId = -1;
        }

        viewer.sendMessage("Replay finished");
        ReplayRegistry.remove(this);
        HandlerList.unregisterAll(this);
    }

    private void copyInventory() {
        this.viewerInventory = viewer.getInventory().getContents().clone();
        this.viewerArmor = viewer.getInventory().getArmorContents().clone();
        this.viewerOffHand = viewer.getInventory().getItemInOffHand().clone();
        viewer.getInventory().clear();
    }

    private void restoreInventory() {
        viewer.getInventory().clear();
        viewer.getInventory().setContents(viewerInventory);
        viewer.getInventory().setArmorContents(viewerArmor);
        viewer.getInventory().setItemInOffHand(viewerOffHand);
        viewer.updateInventory();
    }

    private void clearFakeItems() {
        for (int id : trackedEntityIds) {
            WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(id);
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, destroy);
        }
        trackedEntityIds.clear();
    }

    private void handleEvent(RecordedEntity entity, Map<String, Object> event) {
        String type = (String) event.get("type");
        if (type == null) return; // skip malformed event

        switch (type) {
            case "player_move", "entity_move" -> {
                Double x = asDouble(event.get("x"));
                Double y = asDouble(event.get("y"));
                Double z = asDouble(event.get("z"));
                if (x == null || y == null || z == null) return;

                Location loc = new Location(viewer.getWorld(), x, y, z,
                        asFloat(event.get("yaw")), asFloat(event.get("pitch")));
                entity.moveTo(loc);

                if (event.containsKey("pose") && entity instanceof RecordedPlayer rp) {
                    String poseName = (String) event.get("pose");
                    if (poseName != null) {
                        try {
                            Pose pose = Pose.valueOf(poseName);
                            rp.setPose(pose);
                        } catch (IllegalArgumentException ignored) {
                            // invalid pose string, ignore
                        }
                    }
                }
            }
            case "sneak_start", "sneak_stop", "attack", "block_place", "block_break" -> {
                if (entity instanceof RecordedPlayer rp) {
                    switch (type) {
                        case "sneak_start", "sneak_stop" -> rp.updateSneak(type.equals("sneak_start"));
                        case "attack" -> rp.playAttackAnimation();
                        case "block_place" -> rp.showBlockPlace(event);
                        case "block_break" -> rp.showBlockBreak(event);
                    }
                }
            }
            case "swing" -> {
                if (entity instanceof RecordedPlayer rp) {
                    String hand = (String) event.get("hand");
                    rp.playSwing(hand);
                }
            }

            case "damaged" -> {
                entity.showDamage(event);
            }
            case "sprint_start", "sprint_stop" -> {
                if (entity instanceof RecordedPlayer rp) {
                    rp.updateSprint(type.equals("sprint_start"));
                }
            }
            case "entity_death" -> {
                entity.showDeath(event);
                entity.destroy();
            }
            case "inventory_update" -> {
                if (entity instanceof RecordedPlayer rp) {
                    rp.updateInventory(event);
                }
            }
            case "item_drop" -> {
                Map<String, Object> itemMap = (Map<String, Object>) event.get("item");
                Map<String, Object> locMap = (Map<String, Object>) event.get("location");

                ItemStack stack = deserializeItem(itemMap);
                Location loc = deserializeLocation(locMap);

                if (stack != null && loc != null)
                    spawnFakeDroppedItem(stack, loc);

            }
            case "mob_spawn" -> {
                spawnFakeMob(entity, event);
            }
            case "player_quit" -> {
                UUID uuid = UUID.fromString((String) event.get("uuid"));

                recordedEntities.remove(uuid);
                if (entity == null) {
                    return;
                }

                entity.destroy();
                trackedEntityIds.remove(entity.getFakeEntityId());

            }
        }
    }

    private void spawnFakeMob(RecordedEntity entity, Map<String, Object> event) {
        Location loc = new Location(viewer.getWorld(),
                asDouble(event.get("x")),
                asDouble(event.get("y")),
                asDouble(event.get("z")),
                asFloat(event.get("yaw")),
                asFloat(event.get("pitch")));

        entity.spawn(loc);

        trackedEntityIds.add(entity.getFakeEntityId());
        recordedEntities.put(entity.uuid, entity);

        WrapperPlayServerEntityMetadata meta = new WrapperPlayServerEntityMetadata(
                entity.getFakeEntityId(),
                Collections.emptyList()
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, meta);
    }

    private Double asDouble(Object obj) {
        return obj instanceof Number n ? n.doubleValue() : null;
    }

    private Float asFloat(Object obj) {
        return obj instanceof Number n ? n.floatValue() : 0f;
    }

    private void giveReplayControls(Player viewer) {

        ItemStack pauseButton = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta pauseMeta = pauseButton.getItemMeta();
        pauseMeta.setDisplayName("§cPause / Play");
        pauseButton.setItemMeta(pauseMeta);

        ItemStack skipForward = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta forwardMeta = skipForward.getItemMeta();
        forwardMeta.setDisplayName("§a+5 seconds");
        skipForward.setItemMeta(forwardMeta);

        ItemStack skipBackward = new ItemStack(Material.YELLOW_CONCRETE);
        ItemMeta backwardMeta = skipBackward.getItemMeta();
        backwardMeta.setDisplayName("§e-5 seconds");
        skipBackward.setItemMeta(backwardMeta);

        ItemStack stopReplay = new ItemStack(Material.BARRIER);
        ItemMeta stopMeta = stopReplay.getItemMeta();
        stopMeta.setDisplayName("§4Exit Replay");
        stopReplay.setItemMeta(stopMeta);

        ItemStack playerMenu = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta menuMeta = playerMenu.getItemMeta();
        menuMeta.setDisplayName("§bPlayers");
        playerMenu.setItemMeta(menuMeta);

        viewer.getInventory().setItem(4, pauseButton);
        viewer.getInventory().setItem(5, skipForward);
        viewer.getInventory().setItem(3, skipBackward);
        viewer.getInventory().setItem(6, playerMenu);
        viewer.getInventory().setItem(8, stopReplay);

        viewer.getInventory().setHeldItemSlot(4);
    }


    private Map<String, Object> getInventorySnapshotForPlayer(UUID uuid) {
        if (timeline.isEmpty()) return null;
        Map<String, Object> firstEvent = timeline.get(0);

        if (!uuid.toString().equals(firstEvent.get("uuid")))
            return null;

        Object inventoryObj = firstEvent.get("inventory");
        if (inventoryObj instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> inventory = (Map<String, Object>) map;
            return inventory;
        }
        return null;
    }


    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        Player player = e.getPlayer();
        if (!player.equals(this.viewer))
            return;

        ItemStack handItem = e.getItem();
        if (handItem == null || !handItem.hasItemMeta())
            return;

        String name = handItem.getItemMeta().getDisplayName();

        switch (name) {
            case "§cPause / Play" -> togglePause();
            case "§a+5 seconds" -> skipSeconds(5);
            case "§e-5 seconds" -> skipSeconds(-5);
            case "§4Exit Replay" -> stop();
            case "§bPlayers" -> openPlayerMenu();
        }

        e.setCancelled(true);
    }

    private void togglePause() {
        paused = !paused;
    }

    private void skipSeconds(int seconds) {
        tick += seconds * 20; // Assuming 20 ticks/sec
        if (tick <= 0) tick = 1;
        if (tick >= timeline.size()) tick = timeline.size() - 1;

        Map<String, Object> event = timeline.get(tick);
        for (RecordedEntity entity : recordedEntities.values()) {
            handleEvent(entity, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent e) {
        Player player = (Player) e.getWhoClicked();
        if (!player.equals(viewer))
            return;

        if(!isActive())
            return;

        e.setCancelled(true);

    }

    @EventHandler
    public void onPlayerMenuClick(InventoryClickEvent e) {
        if (!e.getView().getTitle().equals("§8Recorded Players"))
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


        RecordedEntity recorded = recordedEntities.get(target.getUniqueId());
        if (recorded == null)
            return;

        player.teleport(recorded.getCurrentLocation());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (event.getPlayer().equals(viewer))
            stop();
    }


    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() != viewer)
            return;
        if (!isActive())
            return;
        if (!e.getView().getTitle().contains("'s Inventory"))
            return;

        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        Player player = e.getPlayer();

        if (!isActive())
            return;

        if (!player.equals(viewer))
            return;

        ItemStack item = e.getItemDrop().getItemStack();
        if (item == null || !item.hasItemMeta())
            return;

        String name = item.getItemMeta().getDisplayName();
        if (name.equals("§cPause / Play") || name.equals("§a+5 seconds") || name.equals("§e-5 seconds")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityInteract(PlayerInteractAtEntityEvent e) {
        Player viewerPlayer = e.getPlayer();

        if (!viewer.equals(viewerPlayer))
            return;

        if (!(e.getRightClicked() instanceof Player fake))
            return;

        RecordedEntity recordedEntity = recordedEntities.get(fake.getEntityId());
        if (!(recordedEntity instanceof RecordedPlayer rp))
            return;

        rp.openInventoryForViewer(viewerPlayer);

        e.setCancelled(true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY))
            return;

        if (!event.getPlayer().equals(viewer))
            return;

        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);


        if (trackedEntityIds.contains(wrapper.getEntityId()))
            event.setCancelled(true);

        int entityId = wrapper.getEntityId();
        RecordedEntity recordedEntity = recordedEntities.values()
                .stream()
                .filter(e -> e.getFakeEntityId() == entityId)
                .findFirst()
                .orElse(null);

        if (recordedEntity instanceof RecordedPlayer rp) {
            rp.openInventoryForViewer(viewer);
            event.setCancelled(true);
        }
    }

    public RecordedEntity getRecordedEntity(int entityId) {
        for (RecordedEntity e : recordedEntities.values()) {
            if (e.getFakeEntityId() == entityId)
                return e;
        }
        return null;
    }

    private boolean isActive() {
        return ReplayRegistry.contains(this);
    }

    private ItemStack deserializeItem(Map<String, Object> map) {
        if (map == null) return null;

        Material type = Material.valueOf((String) map.get("type"));
        int amount = ((Number) map.get("amount")).intValue();
        ItemStack item = new ItemStack(type, amount);

        if (map.containsKey("displayName") || map.containsKey("lore")) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (map.containsKey("displayName")) meta.setDisplayName((String) map.get("displayName"));
                if (map.containsKey("lore")) meta.setLore((List<String>) map.get("lore"));
                item.setItemMeta(meta);
            }
        }

        return item;
    }

    private Location deserializeLocation(Map<String, Object> map) {
        if (map == null)
            return null;
        double x = ((Number) map.get("x")).doubleValue();
        double y = ((Number) map.get("y")).doubleValue();
        double z = ((Number) map.get("z")).doubleValue();
        float yaw = map.get("yaw") instanceof Number n ? n.floatValue() : 0f;
        float pitch = map.get("pitch") instanceof Number n ? n.floatValue() : 0f;
        String world = map.get("world").toString();

        return new Location(Bukkit.getWorld(world), x, y, z, yaw, pitch);
    }

    private void spawnFakeDroppedItem(ItemStack stack, Location loc) {
        int entityId = SpigotReflectionUtil.generateEntityId();
        trackedEntityIds.add(entityId);

        com.github.retrooper.packetevents.protocol.item.ItemStack nmsStack = SpigotConversionUtil.fromBukkitItemStack(stack);

        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
        entityId,
        UUID.randomUUID(),
        EntityTypes.ITEM,
        SpigotConversionUtil.fromBukkitLocation(loc),
        loc.getYaw(),
        0,
        null
        );

        WrapperPlayServerEntityMetadata meta = new WrapperPlayServerEntityMetadata(
                entityId,
                Collections.singletonList(
                        new EntityData(8, EntityDataTypes.ITEMSTACK, nmsStack)
                )
        );

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, spawn);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, meta);
    }

    private void openPlayerMenu() {
        Inventory inv = Bukkit.createInventory(
                null,
                27,
                "§8Recorded Players"
        );

        for (RecordedEntity entity : recordedEntities.values()) {
            if (!(entity instanceof RecordedPlayer rp))
                continue;

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(rp.getUuid()));
            meta.setDisplayName("§e" + rp.getName());
            head.setItemMeta(meta);

            inv.addItem(head);
        }

        viewer.openInventory(inv);
    }

    private String formatTime(int ticks) {
        int seconds = ticks / 20;
        int minutes = seconds / 60;
        seconds %= 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private void sendActionBar() {
        int totalTicks = timeline.size();

        String current = formatTime(tick);
        String total = formatTime(totalTicks);
        int percent = totalTicks > 0 ? (tick * 100 / totalTicks) : 0;

        Component bar;

        if (paused) {
            bar = Component.text("⏸ Replay paused: ", NamedTextColor.YELLOW)
                    .append(Component.text(current + " / " + total, NamedTextColor.GRAY));
        } else {
            bar = Component.text("▶ Replay: ", NamedTextColor.GREEN)
                    .append(Component.text(current + " / " + total, NamedTextColor.GRAY))
                    .append(Component.text(" (" + percent + "%)", NamedTextColor.DARK_GRAY));
        }
        viewer.sendActionBar(bar);
    }
}

