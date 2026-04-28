package me.justindevb.replay.debug;

public record ReplayDumpQuery(
        Integer startTick,
        Integer endTick
) {

    public ReplayDumpQuery {
        if (startTick != null && startTick < 0) {
            throw new IllegalArgumentException("start tick must be non-negative");
        }
        if (endTick != null && endTick < 0) {
            throw new IllegalArgumentException("end tick must be non-negative");
        }
        if (startTick != null && endTick != null && startTick > endTick) {
            throw new IllegalArgumentException("start tick must be less than or equal to end tick");
        }
    }

    public static ReplayDumpQuery all() {
        return new ReplayDumpQuery(null, null);
    }

    public boolean includesTick(int tick) {
        return (startTick == null || tick >= startTick) && (endTick == null || tick <= endTick);
    }

    public boolean hasTickRange() {
        return startTick != null || endTick != null;
    }

    public int startTickOrDefault() {
        return startTick != null ? startTick : 0;
    }
}