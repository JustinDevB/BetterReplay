package me.justindevb.replay.recording;

import java.util.List;

/**
 * Sealed interface representing all possible timeline event types in a replay recording.
 * Each event carries a tick number and (usually) the UUID of the entity that produced it.
 *
 * <p>Using sealed records instead of raw {@code Map<String, Object>} gives compile-time
 * safety: misspelled keys, wrong casts, and missing fields are caught by the compiler
 * rather than at runtime. Pattern matching in {@code switch} statements lets the compiler
 * verify that every event type is handled.</p>
 *
 * @see TimelineEventAdapter
 */
public sealed interface TimelineEvent {

    /** Game tick when this event was recorded. */
    int tick();

    /** UUID of the entity associated with this event, or {@code null} for events like block_break_stage where the actor may be unknown. */
    String uuid();

    // ── Movement ──────────────────────────────────────────────

    record PlayerMove(int tick, String uuid, String name, String world,
                      double x, double y, double z, float yaw, float pitch,
                      String pose) implements TimelineEvent {}

    record EntityMove(int tick, String uuid, String etype, String world,
                      double x, double y, double z, float yaw, float pitch) implements TimelineEvent {}

    // ── Inventory ─────────────────────────────────────────────

    record InventoryUpdate(int tick, String uuid, String mainHand, String offHand,
                           List<String> armor, List<String> contents) implements TimelineEvent {}

    // ── Blocks ────────────────────────────────────────────────

    record BlockBreak(int tick, String uuid, String world, int x, int y, int z,
                      String blockData) implements TimelineEvent {}

    record BlockBreakComplete(int tick, String uuid, String world,
                              int x, int y, int z) implements TimelineEvent {}

    record BlockBreakStage(int tick, String uuid, String world,
                           int x, int y, int z, int stage) implements TimelineEvent {}

    record BlockPlace(int tick, String uuid, String world, int x, int y, int z,
                      String blockData, String replacedBlockData) implements TimelineEvent {}

    // ── Items ─────────────────────────────────────────────────

    record ItemDrop(int tick, String uuid, String item,
                    String locWorld, double locX, double locY, double locZ,
                    float locYaw, float locPitch) implements TimelineEvent {}

    // ── Combat / Interaction ──────────────────────────────────

    record Attack(int tick, String uuid, String targetUuid,
                  String entityUuid, String entityType) implements TimelineEvent {}

    record Swing(int tick, String uuid, String hand) implements TimelineEvent {}

    record Damaged(int tick, String uuid, String entityType, String cause,
                   double finalDamage) implements TimelineEvent {}

    // ── State toggles ─────────────────────────────────────────

    record SprintToggle(int tick, String uuid, boolean sprinting) implements TimelineEvent {}

    record SneakToggle(int tick, String uuid, boolean sneaking) implements TimelineEvent {}

    // ── Lifecycle ─────────────────────────────────────────────

    record EntitySpawn(int tick, String uuid, String etype, String world,
                       double x, double y, double z) implements TimelineEvent {}

    record EntityDeath(int tick, String uuid, String etype, String world,
                       double x, double y, double z) implements TimelineEvent {}

    record PlayerQuit(int tick, String uuid) implements TimelineEvent {}
}
