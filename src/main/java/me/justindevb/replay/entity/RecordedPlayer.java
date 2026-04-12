package me.justindevb.replay.entity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import com.github.retrooper.packetevents.util.Vector3i;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import me.justindevb.replay.Replay;
import me.justindevb.replay.util.spawning.SpawnFakePlayer;
import net.kyori.adventure.text.Component;
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

import static me.justindevb.replay.util.io.ItemStackSerializer.deserializeItem;

public class RecordedPlayer extends RecordedEntity {
    private final String name;
    private boolean sneaking = false;
    private boolean sprinting = false;

    private byte metadataFlags = 0x00;

    private boolean spawned = false;
    private final UUID uuid;
    private UUID fakeProfileUuid;

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
        SpawnFakePlayer fakePlayer = new SpawnFakePlayer(uuid, name, location, viewer, super.fakeEntityId, () -> {
            this.spawned = true;
            sendMetadata();
        });
        this.fakeProfileUuid = fakePlayer.getFakeUuid();
    }

    private void sendMetadata() {
        if (!spawned) return;
        EntityData<Byte> flagsData = new EntityData<>(0, EntityDataTypes.BYTE, metadataFlags);
        WrapperPlayServerEntityMetadata metadata = new WrapperPlayServerEntityMetadata(fakeEntityId, Collections.singletonList(flagsData));
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, metadata);

        if (currentInventory != null && !currentInventory.isEmpty()) {
            // Reset last-known state so equipment packets are re-sent now that
            // the client has had time to process the entity spawn.
            lastMainHand = null;
            lastOffHand = null;
            Arrays.fill(lastArmor, new ItemStack(Material.AIR));
            showInventorySnapshot(currentInventory);
        }
    }

    public void setPose(Pose pose) {
        EntityData<EntityPose> poseData = new EntityData<>(6, EntityDataTypes.ENTITY_POSE, EntityPose.valueOf(pose.name()));
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

    public UUID getFakeProfileUuid() {
        return fakeProfileUuid;
    }

    public String getName() {
        return name;
    }

    public void updateInventory(Map<String, Object> snapshot) {
        if (snapshot.containsKey("mainHand"))
            currentInventory.put("mainHand", snapshot.get("mainHand"));
        if (snapshot.containsKey("offHand"))
            currentInventory.put("offHand", snapshot.get("offHand"));
        if (snapshot.containsKey("armor"))
            currentInventory.put("armor", snapshot.get("armor"));
        if (snapshot.containsKey("contents"))
            currentInventory.put("contents", snapshot.get("contents"));

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

        EntityData<Byte> flagsData = new EntityData<>(0, EntityDataTypes.BYTE, metadataFlags);
        EntityData<EntityPose> poseData = new EntityData<>(6, EntityDataTypes.ENTITY_POSE, sneaking ? EntityPose.CROUCHING : EntityPose.STANDING);

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

        EntityData<Byte> flagsData = new EntityData<>(0, EntityDataTypes.BYTE, metadataFlags);
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

        EquipmentSlot[] armorSlots = {
                EquipmentSlot.BOOTS,
                EquipmentSlot.LEGGINGS,
                EquipmentSlot.CHEST_PLATE,
                EquipmentSlot.HELMET
        };

        @SuppressWarnings("unchecked")
        List<String> rawArmorList = (List<String>) event.get("armor");

        if (rawArmorList != null) {
            for (int i = 0; i < armorSlots.length; i++) {
                ItemStack armorItem = extractArmor(rawArmorList, i);

                if (!areItemsEqual(armorItem, lastArmor[i])) {
                    lastArmor[i] = armorItem != null ? armorItem.clone() : new ItemStack(Material.AIR);
                    changed = true;
                }
            }
        }

        if (!changed) return;

        packets.add(new Equipment(
                EquipmentSlot.MAIN_HAND,
                SpigotConversionUtil.fromBukkitItemStack(lastMainHand)
        ));

        packets.add(new Equipment(
                EquipmentSlot.OFF_HAND,
                SpigotConversionUtil.fromBukkitItemStack(lastOffHand)
        ));

        for (int i = 0; i < armorSlots.length; i++) {
            packets.add(new Equipment(
                    armorSlots[i],
                    SpigotConversionUtil.fromBukkitItemStack(lastArmor[i])
            ));
        }

        WrapperPlayServerEntityEquipment packet =
                new WrapperPlayServerEntityEquipment(fakeEntityId, packets);

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

    private ItemStack extractArmor(List<String> rawArmorList, int index) {
        if (rawArmorList == null || index >= rawArmorList.size()) {
            return new ItemStack(Material.AIR);
        }

        ItemStack item = deserializeItem(rawArmorList.get(index));
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

        if (!Objects.equals(metaA.displayName(), metaB.displayName())) return false;
        if (!Objects.equals(metaA.lore(), metaB.lore())) return false;

        return true;
    }

    public void openInventoryForViewer(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, 45, Component.text(name + "'s Inventory"));

        @SuppressWarnings("unchecked")
        List<String> contents = (List<String>) currentInventory.get("contents");
        if (contents != null) {
            for (int i = 0; i < contents.size() && i < 36; i++) {
                inv.setItem(i, deserializeItem(contents.get(i)));
            }
        }

        @SuppressWarnings("unchecked")
        List<String> armor = (List<String>) currentInventory.get("armor");
        if (armor != null && armor.size() == 4) {
            inv.setItem(39, deserializeItem(armor.get(3))); // helmet
            inv.setItem(38, deserializeItem(armor.get(2))); // chestplate
            inv.setItem(37, deserializeItem(armor.get(1))); // leggings
            inv.setItem(36, deserializeItem(armor.get(0))); // boots
        }

        inv.setItem(40, deserializeItem(currentInventory.get("offHand")));

        Replay.getInstance().getFoliaLib().getScheduler().runNextTick(task -> {
           viewer.openInventory(inv);
        });
    }


}

