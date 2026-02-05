package me.justindevb.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import com.github.retrooper.packetevents.util.Vector3i;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import me.justindevb.replay.util.SpawnFakePlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class RecordedPlayer extends RecordedEntity {
    private final String name;
    private boolean sneaking = false;
    private boolean sprinting = false;

    private byte metadataFlags = 0x00;

    private boolean spawned = false;
    private final UUID uuid;

    private ItemStack lastMainHand = null;
    private ItemStack lastOffHand = null;
    private final ItemStack[] lastArmor = new ItemStack[] {
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR)
    };


    private Map<String, Object> currentInventory;


    protected RecordedPlayer(UUID uuid, String name, EntityType type, Player viewer) {
        super(uuid, type, viewer);
        this.name = name;
        this.uuid = uuid;
        this.currentInventory = new HashMap<>();
    }

    @Override
    public void spawn(Location location) {
        SpawnFakePlayer fakePlayer = new SpawnFakePlayer(uuid, name, location, viewer, super.fakeEntityId);
        this.spawned = true;

        Bukkit.getScheduler().runTaskLater(Replay.getInstance(), this::sendMetadata, 1L);


    }

    private void sendMetadata() {
        if (!spawned) return;
        EntityData data = new EntityData(0, EntityDataTypes.BYTE, metadataFlags);
        WrapperPlayServerEntityMetadata metadata = new WrapperPlayServerEntityMetadata(fakeEntityId, Collections.singletonList(data));
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, metadata);

        if (currentInventory != null && !currentInventory.isEmpty()) {
            if (spawned)
                showInventorySnapshot(currentInventory);
        }
    }

    public void setPose(Pose pose) {
        EntityData poseData = new EntityData(6, EntityDataTypes.ENTITY_POSE, EntityPose.valueOf(pose.name()));
        WrapperPlayServerEntityMetadata metadata = new WrapperPlayServerEntityMetadata(
                fakeEntityId,
                Collections.singletonList(poseData)
        );

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, metadata);
    }

    @Override
    public void moveTo(Location loc) {
        WrapperPlayServerEntityTeleport tp = new WrapperPlayServerEntityTeleport(
                fakeEntityId,
                SpigotConversionUtil.fromBukkitLocation(loc),
                true
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, tp);

        byte headYaw = (byte) ((loc.getYaw() * 256f) / 360f);
        WrapperPlayServerEntityHeadLook headLook = new WrapperPlayServerEntityHeadLook(fakeEntityId, headYaw);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, headLook);
        super.currentLocation = loc;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void updateInventory(Map<String, Object> snapshot) {
        this.currentInventory = snapshot;

        if (!spawned)
            return;

        showInventorySnapshot(currentInventory);

    }



    // ---------------------
    // Replay-specific methods
    // ---------------------

    public void updateSneak(boolean sneaking) {
        if (this.sneaking == sneaking) return;
        this.sneaking = sneaking;

        if (sneaking) {
            metadataFlags |= 0x02; // sneaking bit
        } else {
            metadataFlags &= ~0x02;
        }

        EntityData flagsData = new EntityData(0, EntityDataTypes.BYTE, metadataFlags);
        EntityData poseData = new EntityData(6, EntityDataTypes.ENTITY_POSE, sneaking ? EntityPose.CROUCHING : EntityPose.STANDING);

        WrapperPlayServerEntityMetadata metadata = new WrapperPlayServerEntityMetadata(
                fakeEntityId,
                Arrays.asList(flagsData, poseData)
        );

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, metadata);
    }

    public void updateSprint(boolean sprinting) {
        if (this.sprinting == sprinting) return;
        this.sprinting = sprinting;

        if (sprinting) {
            metadataFlags |= 0x08; // sprint bit
        } else {
            metadataFlags &= ~0x08;
        }

        EntityData flagsData = new EntityData(0, EntityDataTypes.BYTE, metadataFlags);
        WrapperPlayServerEntityMetadata metadata =
                new WrapperPlayServerEntityMetadata(fakeEntityId, Collections.singletonList(flagsData));

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, metadata);
    }

    public void playAttackAnimation() {
        WrapperPlayServerEntityAnimation anim = new WrapperPlayServerEntityAnimation(fakeEntityId, WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, anim);
    }

    public void showBlockPlace(Map<String, Object> event) {
        WrapperPlayServerEntityAnimation anim = new WrapperPlayServerEntityAnimation(fakeEntityId, WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, anim);
    }

    public void showBlockBreak(Map<String, Object> event) {
        int x = ((Number) event.get("x")).intValue();
        int y = ((Number) event.get("y")).intValue();
        int z = ((Number) event.get("z")).intValue();

        int stage = ((Number) event.getOrDefault("stage", 9)).intValue();

        WrapperPlayServerBlockBreakAnimation breakAnim =
                new WrapperPlayServerBlockBreakAnimation(fakeEntityId, new Vector3i(x, y, z), (byte) stage);

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, breakAnim);
    }

    public void playSwing(String hand) {
        WrapperPlayServerEntityAnimation.EntityAnimationType anim;
        if ("OFF_HAND".equalsIgnoreCase(hand)) {
            anim = WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_OFF_HAND;
        } else {
            anim = WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM;
        }

        WrapperPlayServerEntityAnimation swing =
                new WrapperPlayServerEntityAnimation(fakeEntityId, anim);
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, swing);

    }

    public void showInventorySnapshot(Map<String, Object> event) {
        boolean changed = false;
        List<Equipment> packets = new ArrayList<>();

        ItemStack mainHand = deserializeItem(event.get("mainHand"));

        if (!areItemsEqual(mainHand, lastMainHand)) {
            lastMainHand = mainHand != null ? mainHand.clone() : new ItemStack(Material.AIR);
            changed = true;
        }

        ItemStack offHand = deserializeItem(event.get("offHand"));
        if (!areItemsEqual(offHand, lastOffHand)) {
            lastOffHand = offHand != null ? offHand.clone() : new ItemStack(Material.AIR);
            changed = true;
        }

        EquipmentSlot[] armorSlots = {EquipmentSlot.BOOTS, EquipmentSlot.LEGGINGS, EquipmentSlot.CHEST_PLATE, EquipmentSlot.HELMET};
        List<Map<String, Object>> rawArmorList =
                (List<Map<String, Object>>) event.get("armor");

        if (rawArmorList != null) {
            for (int i = 0; i < armorSlots.length; i++) {
                ItemStack armorItem = extractArmor(rawArmorList, i);

                if (!areItemsEqual(armorItem, lastArmor[i])) {
                    lastArmor[i] = armorItem.clone();
                    changed = true;
                }
            }
        }

        if (!changed) return;

        packets.add(new Equipment(EquipmentSlot.MAIN_HAND,
                SpigotConversionUtil.fromBukkitItemStack(lastMainHand)));

        packets.add(new Equipment(EquipmentSlot.OFF_HAND,
                SpigotConversionUtil.fromBukkitItemStack(lastOffHand)));

        for (int i = 0; i < armorSlots.length; i++) {
            packets.add(new Equipment(armorSlots[i],
                    SpigotConversionUtil.fromBukkitItemStack(lastArmor[i])));
        }

        WrapperPlayServerEntityEquipment packet =
                new WrapperPlayServerEntityEquipment(fakeEntityId, packets);

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

    private ItemStack extractArmor(List<Map<String, Object>> rawArmorList, int index) {
        if (rawArmorList == null || index >= rawArmorList.size()) {
            return new ItemStack(Material.AIR);
        }

        Object obj = rawArmorList.get(index);
        if (!(obj instanceof Map<?, ?> armorMap)) {
            return new ItemStack(Material.AIR);
        }

        ItemStack item = deserializeItem((Map<String, Object>) armorMap);
        return item != null ? item : new ItemStack(Material.AIR);
    }

    private boolean areItemsEqual(ItemStack a, ItemStack b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.getType() != b.getType()) return false;
        if (a.getAmount() != b.getAmount()) return false;

        ItemMeta metaA = a.getItemMeta();
        ItemMeta metaB = b.getItemMeta();

        if (metaA == null && metaB == null) return true;
        if (metaA == null || metaB == null) return false;

        if (!Objects.equals(metaA.getDisplayName(), metaB.getDisplayName())) return false;
        if (!Objects.equals(metaA.getLore(), metaB.getLore())) return false;

        return true;
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

    public void openInventoryForViewer(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, 45, name + "'s Inventory");

        List<Map<String, Object>> contents = (List<Map<String, Object>>) currentInventory.get("contents");
        if (contents != null) {
            for (int i = 0; i < contents.size() && i < 36; i++) {
                inv.setItem(i, deserializeItem(contents.get(i)));
            }
        }

        List<Map<String, Object>> armor = (List<Map<String, Object>>) currentInventory.get("armor");
        if (armor != null && armor.size() == 4) {
            inv.setItem(39, deserializeItem(armor.get(3)));
            inv.setItem(38, deserializeItem(armor.get(2)));
            inv.setItem(37, deserializeItem(armor.get(1)));
            inv.setItem(36, deserializeItem(armor.get(0)));
        }

        inv.setItem(40, deserializeItem(currentInventory.get("offHand")));

        Bukkit.getScheduler().runTask(Replay.getInstance(), () -> viewer.openInventory(inv));
    }




    private ItemStack deserializeItem(Object obj) {
        if (!(obj instanceof Map<?, ?> map)) return null;
        Material type = Material.getMaterial((String) map.get("type"));
        if (type == null) return null;

        int amount = map.get("amount") instanceof Number n ? n.intValue() : 1;
        ItemStack item = new ItemStack(type, amount);

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (map.containsKey("displayName")) meta.setDisplayName((String) map.get("displayName"));
            if (map.containsKey("lore")) meta.setLore((List<String>) map.get("lore"));
            item.setItemMeta(meta);
        }

        return item;
    }


}

