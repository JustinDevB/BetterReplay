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
import com.google.gson.reflect.TypeToken;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public class ReplaySession implements Listener, PacketListener {
    private final File file;
    private final Player viewer;
    private final Replay replay;
    private final Gson gson = new Gson();

    private List<Map<String, Object>> timeline;
    private List<Integer> trackedEntityIds = new ArrayList<>();
    Map<UUID, RecordedEntity> recordedEntities = new HashMap<>();
    private int tick = 0;
    private boolean paused = false;
    private final Set<Integer> controlSlots = Set.of(0, 1, 2);
    private ItemStack[] viewerInventory;
    private ItemStack[] viewerArmor;
    private ItemStack viewerOffHand;

    public ReplaySession(File file, Player viewer, Replay replay) {
        this.file = file;
        this.viewer = viewer;
        this.replay = replay;
        Bukkit.getPluginManager().registerEvents(this, Replay.getInstance());

    }

    public void start() {
        viewer.sendMessage("Starting replay: " + file.getName());
        try {
            String json = Files.readString(file.toPath());
            timeline = gson.fromJson(json, new TypeToken<List<Map<String, Object>>>() {
            }.getType());
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        if (!timeline.isEmpty()) {
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
        }

        giveReplayControls(viewer);

        new BukkitRunnable() {
            @Override
            public void run() {
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

                Map<String, Object> event = timeline.get(tick);

                // Validate UUID
                Object uuidObj = event.get("uuid");
                if (!(uuidObj instanceof String)) {
                    tick++;
                    System.out.println("malformed event: uuid missing");
                    return;
                }
                UUID uuid = UUID.fromString((String) event.get("uuid"));
                RecordedEntity recorded = recordedEntities.computeIfAbsent(uuid, id -> {
                    Double x = asDouble(event.get("x")), y = asDouble(event.get("y")), z = asDouble(event.get("z"));
                    if (x == null || y == null || z == null) return null; // skip if missing coordinates

                    Location initialLoc = new Location(viewer.getWorld(), x, y, z,
                            asFloat(event.get("yaw")), asFloat(event.get("pitch")));

                    RecordedEntity entity = RecordedEntityFactory.create(event, viewer);
                    if (entity == null) {
                        System.out.println("Malformed event: missing or invalid entity type for UUID " + uuid);
                        return null;
                    }

                    entity.spawn(initialLoc);
                    if (entity instanceof RecordedPlayer rp) {
                        // Pull tick 0 inventory from timeline
                        Map<String, Object> tick0Inventory = getInventorySnapshotForPlayer(uuid);
                        if (tick0Inventory != null) {
                            rp.updateInventory(tick0Inventory); // This sends the equipment packet
                        }
                    }

                    return entity;
                });

                if (recorded != null) handleEvent(recorded, event);

                if (!paused)
                    tick++;
            }
        }.runTaskTimer(replay, 1L, 1L);
    }

    public void stop() {
        recordedEntities.values().forEach(RecordedEntity::destroy);
        recordedEntities.clear();
        viewer.sendMessage("Replay finished");

        ReplayRegistry.remove(this);

        restoreInventory();

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
                Double x = asDouble(event.get("x")), y = asDouble(event.get("y")), z = asDouble(event.get("z"));
                if (x == null || y == null || z == null) return;

                Location loc = new Location(viewer.getWorld(), x, y, z,
                        asFloat(event.get("yaw")), asFloat(event.get("pitch")));
                entity.moveTo(loc);
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
        }
    }

    // Helpers
    private Double asDouble(Object obj) {
        return obj instanceof Number n ? n.doubleValue() : null;
    }

    private Float asFloat(Object obj) {
        return obj instanceof Number n ? n.floatValue() : 0f;
    }

    private void giveReplayControls(Player viewer) {
        ItemStack[] items = new ItemStack[3];

        // Create items in order
        ItemStack pauseButton = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta pauseMeta = pauseButton.getItemMeta();
        pauseMeta.setDisplayName("§cPause / Play");
        pauseButton.setItemMeta(pauseMeta);
        items[0] = pauseButton;

        ItemStack skipForward = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta forwardMeta = skipForward.getItemMeta();
        forwardMeta.setDisplayName("§a+5 seconds");
        skipForward.setItemMeta(forwardMeta);
        items[1] = skipForward;

        ItemStack skipBackward = new ItemStack(Material.YELLOW_CONCRETE);
        ItemMeta backwardMeta = skipBackward.getItemMeta();
        backwardMeta.setDisplayName("§e-5 seconds");
        skipBackward.setItemMeta(backwardMeta);
        items[2] = skipBackward;

        // Assign items to slots in order
        int i = 0;
        for (int slot : controlSlots) {
            if (i >= items.length)
                break;
            viewer.getInventory().setItem(slot, items[i]);
            i++;
        }
    }


    private Map<String, Object> getInventorySnapshotForPlayer(UUID uuid) {
        if (timeline.isEmpty()) return null;
        Map<String, Object> firstEvent = timeline.get(0);

        // Only return the inventory part if it matches the UUID
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
            return; // Only for the replay viewer

        ItemStack handItem = e.getItem();
        if (handItem == null || !handItem.hasItemMeta())
            return;

        String name = handItem.getItemMeta().getDisplayName();

        switch (name) {
            case "§cPause / Play" -> togglePause();
            case "§a+5 seconds" -> skipSeconds(5);
            case "§e-5 seconds" -> skipSeconds(-5);
        }

        e.setCancelled(true); // Prevent any default use (placing blocks, etc.)
    }

    private void togglePause() {
        paused = !paused;
    }

    private void skipSeconds(int seconds) {
        tick += seconds * 20; // Assuming 20 ticks/sec
        if (tick <= 0) tick = 1;
        if (tick >= timeline.size()) tick = timeline.size() - 1;

        // Immediately update all entities to the new tick
        Map<String, Object> event = timeline.get(tick);
        for (RecordedEntity entity : recordedEntities.values()) {
            handleEvent(entity, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent e) {
        Player player = (Player) e.getWhoClicked();
        if (!player.equals(viewer))
            return; // Only for the replay viewer

        if(!isActive())
            return;
        // Check if the clicked slot is one of the controls
        if (controlSlots.contains(e.getSlot())) {
            e.setCancelled(true);
        }

        if (e.getView().getTitle().contains("'s Inventory")) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() != viewer)
            return;
        if (isActive())
            return;
        if (!e.getView().getTitle().contains("'s Inventory"))
            return;

        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        Player player = e.getPlayer();

        if (isActive())
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

        // Only care about the viewer
        if (!viewer.equals(viewerPlayer))
            return;

        if (!(e.getRightClicked() instanceof Player fake))
            return;

        RecordedEntity recordedEntity = recordedEntities.get(fake.getEntityId());
        if (!(recordedEntity instanceof RecordedPlayer rp))
            return;

        // Open the recorded player's inventory snapshot
        rp.openInventoryForViewer(viewerPlayer);

        e.setCancelled(true); // Prevent interacting with the fake player
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY))
            return;

        // Only care about the viewer’s interactions
        if (!event.getPlayer().equals(viewer))
            return;

        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);

        /*
        Prevent picking up fake items dropped in a replay
         */
        if (trackedEntityIds.contains(wrapper.getEntityId()))
            event.setCancelled(true);

        int entityId = wrapper.getEntityId();
        RecordedEntity recordedEntity = recordedEntities.values()
                .stream()
                .filter(e -> e.getFakeEntityId() == entityId) // you'll need getFakeEntityId() accessor
                .findFirst()
                .orElse(null);

        if (recordedEntity instanceof RecordedPlayer rp) {
            // Open the fake inventory snapshot
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

        // Optional: simple meta reconstruction
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

}

