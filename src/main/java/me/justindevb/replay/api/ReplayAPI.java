package me.justindevb.replay.api;

import org.jetbrains.annotations.NotNull;

public final class ReplayAPI {

    private static ReplayManager manager;

    private ReplayAPI() {}

    public static void init(ReplayManager replayManager) {
        if (manager != null)
            throw new IllegalStateException("ReplayAPI already initialized!");
        manager = replayManager;
    }

    public static @NotNull ReplayManager get() {
        if (manager == null)
            throw new IllegalStateException("ReplayAPI has not been initialized");
        return manager;
    }

    public static void shutdown() {
        manager = null;
    }
}
