package me.justindevb.replay.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.recording.TimelineEventAdapter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VersionUtilTest {

    // ── isAtLeast ─────────────────────────────────────────────

    @Nested
    class IsAtLeast {

        @Test
        void sameVersion() {
            assertTrue(VersionUtil.isAtLeast("1.4.0", "1.4.0"));
        }

        @Test
        void releaseMeetsSameBaseAlphaRequirement() {
            assertTrue(VersionUtil.isAtLeast("1.5.0", "1.5.0-alpha.9"));
        }

        @Test
        void alphaDoesNotMeetSameBaseReleaseRequirement() {
            assertFalse(VersionUtil.isAtLeast("1.5.0-alpha.9", "1.5.0"));
        }

        @Test
        void runningHigherMajor() {
            assertTrue(VersionUtil.isAtLeast("2.0.0", "1.4.0"));
        }

        @Test
        void runningHigherMinor() {
            assertTrue(VersionUtil.isAtLeast("1.5.0", "1.4.0"));
        }

        @Test
        void runningHigherPatch() {
            assertTrue(VersionUtil.isAtLeast("1.4.1", "1.4.0"));
        }

        @Test
        void runningLowerMajor() {
            assertFalse(VersionUtil.isAtLeast("0.9.0", "1.4.0"));
        }

        @Test
        void runningLowerMinor() {
            assertFalse(VersionUtil.isAtLeast("1.3.9", "1.4.0"));
        }

        @Test
        void runningLowerPatch() {
            assertFalse(VersionUtil.isAtLeast("1.4.0", "1.4.1"));
        }

        @Test
        void olderAlphaBuildDoesNotMeetNewerAlphaRequirement() {
            assertFalse(VersionUtil.isAtLeast("1.5.0-alpha.9", "1.5.0-alpha.10"));
        }

        @Test
        void snapshotSuffix() {
            assertTrue(VersionUtil.isAtLeast("1.4.0-SNAPSHOT", "1.4.0"));
            assertTrue(VersionUtil.isAtLeast("1.4.0", "1.4.0-SNAPSHOT"));
        }

        @Test
        void mismatchedSegmentLengths() {
            assertTrue(VersionUtil.isAtLeast("1.4", "1.4.0"));
            assertTrue(VersionUtil.isAtLeast("1.4.0", "1.4"));
            assertFalse(VersionUtil.isAtLeast("1.3", "1.4.0"));
        }

        @Test
        void singleSegment() {
            assertTrue(VersionUtil.isAtLeast("2", "1"));
            assertFalse(VersionUtil.isAtLeast("1", "2"));
        }
    }

    // ── wrapTimeline ──────────────────────────────────────────

    @Nested
    class WrapTimeline {

        private final Gson gson = new GsonBuilder()
                .registerTypeHierarchyAdapter(TimelineEvent.class, new TimelineEventAdapter())
                .create();

        @Test
        void producesEnvelopeWithRequiredFields() {
            List<TimelineEvent> timeline = List.of(
                    new TimelineEvent.PlayerQuit(0, "abc-123")
            );

            String json = VersionUtil.wrapTimeline(gson, timeline, "1.4.0");
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            assertEquals("1.4.0", obj.get("createdBy").getAsString());
            assertEquals(VersionUtil.MIN_RECORDING_VERSION, obj.get("minVersion").getAsString());
            assertTrue(obj.has("timeline"));
            assertTrue(obj.get("timeline").isJsonArray());
            assertEquals(1, obj.getAsJsonArray("timeline").size());
        }

        @Test
        void emptyTimeline() {
            String json = VersionUtil.wrapTimeline(gson, List.of(), "1.4.0");
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            assertTrue(obj.getAsJsonArray("timeline").isEmpty());
        }
    }

    // ── parseReplayJson ───────────────────────────────────────

    @Nested
    class ParseReplayJson {

        private final Gson gson = new GsonBuilder()
                .registerTypeHierarchyAdapter(TimelineEvent.class, new TimelineEventAdapter())
                .create();
        private static final Type LIST_TYPE = new TypeToken<List<TimelineEvent>>() {}.getType();

        @Test
        void parsesEnvelopeFormat() {
            List<TimelineEvent> original = List.of(
                    new TimelineEvent.PlayerQuit(5, "uuid-1")
            );
            String json = VersionUtil.wrapTimeline(gson, original, "1.4.0");

            List<TimelineEvent> parsed = VersionUtil.parseReplayJson(gson, json, VersionUtil.MIN_RECORDING_VERSION, LIST_TYPE);

            assertEquals(1, parsed.size());
            assertInstanceOf(TimelineEvent.PlayerQuit.class, parsed.get(0));
            assertEquals(5, parsed.get(0).tick());
        }

        @Test
        void parsesLegacyRawArray() {
            String json = """
                    [{"type":"player_quit","tick":10,"uuid":"uuid-2"}]
                    """;

            List<TimelineEvent> parsed = VersionUtil.parseReplayJson(gson, json, "1.4.0", LIST_TYPE);

            assertEquals(1, parsed.size());
            assertEquals("uuid-2", parsed.get(0).uuid());
        }

        @Test
        void throwsOnVersionMismatch() {
            String json = """
                    {"createdBy":"2.0.0","minVersion":"2.0.0","timeline":[]}
                    """;

            VersionUtil.ReplayVersionMismatchException ex = assertThrows(
                    VersionUtil.ReplayVersionMismatchException.class,
                    () -> VersionUtil.parseReplayJson(gson, json, "1.4.0", LIST_TYPE)
            );

            assertEquals("2.0.0", ex.getRequiredVersion());
            assertEquals("1.4.0", ex.getRunningVersion());
        }

        @Test
        void passesWhenRunningVersionMeetsRequirement() {
            String json = """
                    {"createdBy":"1.4.0","minVersion":"1.4.0","timeline":[{"type":"player_quit","tick":1,"uuid":"a"}]}
                    """;

            List<TimelineEvent> parsed = VersionUtil.parseReplayJson(gson, json, "1.5.0", LIST_TYPE);
            assertEquals(1, parsed.size());
        }

        @Test
        void envelopeWithoutMinVersionSkipsCheck() {
            String json = """
                    {"createdBy":"1.3.0","timeline":[{"type":"player_quit","tick":1,"uuid":"a"}]}
                    """;

            List<TimelineEvent> parsed = VersionUtil.parseReplayJson(gson, json, "1.4.0", LIST_TYPE);
            assertEquals(1, parsed.size());
        }
    }

    // ── compareVersions ───────────────────────────────────────

    @Nested
    class CompareVersions {

        @Test
        void sameRelease() {
            assertEquals(0, VersionUtil.compareVersions("1.4.0", "1.4.0"));
        }

        @Test
        void newerMajor() {
            assertTrue(VersionUtil.compareVersions("2.0.0", "1.4.0") > 0);
        }

        @Test
        void newerMinor() {
            assertTrue(VersionUtil.compareVersions("1.5.0", "1.4.0") > 0);
        }

        @Test
        void olderRelease() {
            assertTrue(VersionUtil.compareVersions("1.3.0", "1.4.0") < 0);
        }

        @Test
        void alphaLessThanSameBaseRelease() {
            assertTrue(VersionUtil.compareVersions("1.4.0-alpha.5", "1.4.0") < 0);
        }

        @Test
        void releaseGreaterThanSameBaseAlpha() {
            assertTrue(VersionUtil.compareVersions("1.4.0", "1.4.0-alpha.5") > 0);
        }

        @Test
        void newerAlphaBuild() {
            assertTrue(VersionUtil.compareVersions("1.4.0-alpha.5", "1.4.0-alpha.3") > 0);
        }

        @Test
        void olderAlphaBuild() {
            assertTrue(VersionUtil.compareVersions("1.4.0-alpha.3", "1.4.0-alpha.5") < 0);
        }

        @Test
        void sameAlphaBuild() {
            assertEquals(0, VersionUtil.compareVersions("1.4.0-alpha.5", "1.4.0-alpha.5"));
        }

        @Test
        void alphaOfNewerBaseBeatsCurrent() {
            // 1.5.0-alpha.1 is newer than 1.4.0 release (different base)
            assertTrue(VersionUtil.compareVersions("1.5.0-alpha.1", "1.4.0") > 0);
        }

        @Test
        void releaseNewerThanAlphaOfOlderBase() {
            assertTrue(VersionUtil.compareVersions("1.5.0", "1.4.0-alpha.5") > 0);
        }

        @Test
        void snapshotTreatedAsRelease() {
            // -SNAPSHOT is not -alpha, so it's treated as a release
            assertEquals(0, VersionUtil.compareVersions("1.4.0-SNAPSHOT", "1.4.0"));
        }
    }

    // ── isAlpha ───────────────────────────────────────────────

    @Nested
    class IsAlpha {

        @Test
        void alphaVersion() {
            assertTrue(VersionUtil.isAlpha("1.4.0-alpha.5"));
        }

        @Test
        void releaseVersion() {
            assertFalse(VersionUtil.isAlpha("1.4.0"));
        }

        @Test
        void snapshotNotAlpha() {
            assertFalse(VersionUtil.isAlpha("1.4.0-SNAPSHOT"));
        }
    }
}
