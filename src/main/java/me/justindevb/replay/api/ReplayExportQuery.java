package me.justindevb.replay.api;

/**
 * Export-time replay filters. Null or blank values mean "all".
 */
public record ReplayExportQuery(String player, Integer startTick, Integer endTick) {

    public ReplayExportQuery {
        if (startTick != null && startTick < 0) {
            throw new IllegalArgumentException("startTick must be non-negative");
        }
        if (endTick != null && endTick < 0) {
            throw new IllegalArgumentException("endTick must be non-negative");
        }
        if (startTick != null && endTick != null && startTick > endTick) {
            throw new IllegalArgumentException("startTick must be less than or equal to endTick");
        }
    }

    public static ReplayExportQuery all() {
        return new ReplayExportQuery(null, null, null);
    }

    public boolean isAllPlayers() {
        return player == null || player.isBlank() || "all".equalsIgnoreCase(player);
    }

    public boolean hasTickRange() {
        return startTick != null || endTick != null;
    }

    public int startTickOrDefault() {
        return startTick != null ? startTick : 0;
    }

    public boolean includesTick(int tick) {
        if (startTick != null && tick < startTick) {
            return false;
        }
        return endTick == null || tick <= endTick;
    }
}