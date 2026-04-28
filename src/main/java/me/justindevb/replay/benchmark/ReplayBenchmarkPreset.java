package me.justindevb.replay.benchmark;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public enum ReplayBenchmarkPreset {
    SMALL(1, 2_400),
    MEDIUM(4, 12_000),
    LARGE(12, 36_000);

    private final int players;
    private final int durationTicks;

    ReplayBenchmarkPreset(int players, int durationTicks) {
        this.players = players;
        this.durationTicks = durationTicks;
    }

    public int players() {
        return players;
    }

    public int durationTicks() {
        return durationTicks;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<ReplayBenchmarkPreset> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(value -> value.id().equals(raw.toLowerCase(Locale.ROOT)))
                .findFirst();
    }

    public static List<String> ids() {
        return Arrays.stream(values()).map(ReplayBenchmarkPreset::id).toList();
    }
}