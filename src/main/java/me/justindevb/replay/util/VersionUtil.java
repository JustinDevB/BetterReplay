package me.justindevb.replay.util;

import com.google.gson.*;

import java.lang.reflect.Type;
import java.util.List;

public final class VersionUtil {

    /** Minimum plugin version required to read recordings produced by this build. */
    public static final String MIN_RECORDING_VERSION = "1.4.0";

    private VersionUtil() {}

    /**
     * Returns true if {@code running} is greater than or equal to {@code required}.
     * Supports alpha pre-release ordering (e.g. "1.4.0" >= "1.4.0-alpha.5").
     */
    public static boolean isAtLeast(String running, String required) {
        return compareVersions(running, required) >= 0;
    }

    /**
     * Compares two version strings with alpha-build support.
     * <p>
     * Ordering examples: {@code 1.3.0 < 1.4.0-alpha.1 < 1.4.0-alpha.5 < 1.4.0}
     *
     * @return negative if {@code a} is older than {@code b}, 0 if equal, positive if {@code a} is newer
     */
    public static int compareVersions(String a, String b) {
        String baseA = a.split("-")[0];
        String baseB = b.split("-")[0];

        int baseCmp = compareBase(baseA, baseB);
        if (baseCmp != 0) return baseCmp;

        // Same base – compare pre-release status
        boolean alphaA = a.contains("-alpha");
        boolean alphaB = b.contains("-alpha");

        if (!alphaA && !alphaB) return 0;      // both releases
        if (alphaA && !alphaB)  return -1;     // alpha < release
        if (!alphaA)            return 1;      // release > alpha

        // Both alpha with same base – compare build numbers
        return Integer.compare(extractAlphaBuild(a), extractAlphaBuild(b));
    }

    /**
     * Returns true if the given version string denotes an alpha/dev build.
     */
    public static boolean isAlpha(String version) {
        return version.contains("-alpha");
    }

    private static int compareBase(String a, String b) {
        String[] sa = a.split("\\.");
        String[] sb = b.split("\\.");
        int len = Math.max(sa.length, sb.length);
        for (int i = 0; i < len; i++) {
            int va = i < sa.length ? parseSegment(sa[i]) : 0;
            int vb = i < sb.length ? parseSegment(sb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int extractAlphaBuild(String version) {
        int idx = version.indexOf("-alpha.");
        if (idx < 0) return 0;
        try {
            return Integer.parseInt(version.substring(idx + 7));
        } catch (NumberFormatException e) {
            return 0;
        }
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
    public static <T> List<T> parseReplayJson(Gson gson, String json, String runningVersion, Type listType) {
        JsonElement el = JsonParser.parseString(json);

        if (el.isJsonArray()) {
            // Legacy format: raw timeline array, no version check possible
            return gson.fromJson(el, listType);
        }

        JsonObject obj = el.getAsJsonObject();
        if (obj.has("minVersion")) {
            String required = obj.get("minVersion").getAsString();
            if (!isAtLeast(runningVersion, required)) {
                throw new ReplayVersionMismatchException(required, runningVersion);
            }
        }
        return gson.fromJson(obj.get("timeline"), listType);
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
