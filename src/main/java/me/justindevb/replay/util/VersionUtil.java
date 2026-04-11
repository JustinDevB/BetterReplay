package me.justindevb.replay.util;

import com.google.gson.*;

import java.util.List;

public final class VersionUtil {

    /** Minimum plugin version required to read recordings produced by this build. */
    public static final String MIN_RECORDING_VERSION = "1.4.0";

    private VersionUtil() {}

    /**
     * Returns true if {@code running} is greater than or equal to {@code required}.
     * Compares dot-separated integer segments (e.g. "1.4.0" >= "1.4.0").
     */
    public static boolean isAtLeast(String running, String required) {
        String[] r = running.split("\\.");
        String[] q = required.split("\\.");
        int len = Math.max(r.length, q.length);
        for (int i = 0; i < len; i++) {
            int rv = i < r.length ? parseSegment(r[i]) : 0;
            int qv = i < q.length ? parseSegment(q[i]) : 0;
            if (rv != qv) return rv > qv;
        }
        return true;
    }

    private static int parseSegment(String s) {
        // Strip non-numeric suffixes like "-SNAPSHOT"
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        if (end == 0) return 0;
        try {
            return Integer.parseInt(s.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Wraps a timeline list in an envelope JSON object with version metadata.
     */
    public static String wrapTimeline(Gson gson, List<?> timeline, String pluginVersion) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("createdBy", pluginVersion);
        envelope.addProperty("minVersion", MIN_RECORDING_VERSION);
        envelope.add("timeline", gson.toJsonTree(timeline));
        return gson.toJson(envelope);
    }

    /**
     * Parses replay JSON, handling both legacy (raw array) and new (envelope) formats.
     * Checks version compatibility when an envelope is present.
     *
     * @throws ReplayVersionMismatchException if the recording requires a newer plugin version
     */
    public static List<?> parseReplayJson(Gson gson, String json, String runningVersion) {
        JsonElement el = JsonParser.parseString(json);

        if (el.isJsonArray()) {
            // Legacy format: raw timeline array, no version check possible
            return gson.fromJson(el, List.class);
        }

        JsonObject obj = el.getAsJsonObject();
        if (obj.has("minVersion")) {
            String required = obj.get("minVersion").getAsString();
            if (!isAtLeast(runningVersion, required)) {
                throw new ReplayVersionMismatchException(required, runningVersion);
            }
        }
        return gson.fromJson(obj.get("timeline"), List.class);
    }

    /**
     * Thrown when a recording requires a newer plugin version than is currently running.
     */
    public static class ReplayVersionMismatchException extends RuntimeException {
        private final String requiredVersion;
        private final String runningVersion;

        public ReplayVersionMismatchException(String requiredVersion, String runningVersion) {
            super("Recording requires BetterReplay v" + requiredVersion + "+, running v" + runningVersion);
            this.requiredVersion = requiredVersion;
            this.runningVersion = runningVersion;
        }

        public String getRequiredVersion() { return requiredVersion; }
        public String getRunningVersion() { return runningVersion; }
    }
}
