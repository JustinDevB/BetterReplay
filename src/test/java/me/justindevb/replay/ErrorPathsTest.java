package me.justindevb.replay;

import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.util.io.ReplayCompressor;
import me.justindevb.replay.util.VersionUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import me.justindevb.replay.recording.TimelineEventAdapter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for error handling and edge-case inputs in compression / version utilities.
 */
class ErrorPathsTest {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeHierarchyAdapter(TimelineEvent.class, new TimelineEventAdapter())
            .create();
    private static final Type LIST_TYPE = new TypeToken<List<TimelineEvent>>() {}.getType();

    // ── ReplayCompressor edge cases ───────────────────────────

    @Nested
    class CompressorEdgeCases {

        @Test
        void compress_emptyString() throws IOException {
            byte[] compressed = ReplayCompressor.compress("");
            assertTrue(ReplayCompressor.isGzipCompressed(compressed));
            assertEquals("", ReplayCompressor.decompress(compressed));
        }

        @Test
        void decompress_notGzip_throwsIOException() {
            byte[] badData = {0x00, 0x01, 0x02, 0x03};
            assertThrows(IOException.class, () -> ReplayCompressor.decompress(badData));
        }

        @Test
        void decompressIfNeeded_plainText_returnsAsIs() throws IOException {
            String original = "{\"hello\":\"world\"}";
            byte[] bytes = original.getBytes(StandardCharsets.UTF_8);
            assertEquals(original, ReplayCompressor.decompressIfNeeded(bytes));
        }

        @Test
        void isGzipCompressed_tooShort_returnsFalse() {
            assertFalse(ReplayCompressor.isGzipCompressed(new byte[]{0x1f}));
            assertFalse(ReplayCompressor.isGzipCompressed(new byte[0]));
        }

        @Test
        void roundtrip_unicodeContent() throws IOException {
            String unicode = "日本語テスト \uD83D\uDE00 данные";
            byte[] compressed = ReplayCompressor.compress(unicode);
            assertEquals(unicode, ReplayCompressor.decompress(compressed));
        }
    }

    // ── VersionUtil edge cases ────────────────────────────────

    @Nested
    class VersionUtilEdgeCases {

        @Test
        void isAtLeast_equalVersions() {
            assertTrue(VersionUtil.isAtLeast("1.4.0", "1.4.0"));
        }

        @Test
        void isAtLeast_snapshotTreatedAsRelease() {
            assertTrue(VersionUtil.isAtLeast("1.4.0-SNAPSHOT", "1.4.0"));
        }

        @Test
        void isAtLeast_singleDigitVersions() {
            assertTrue(VersionUtil.isAtLeast("2", "1"));
            assertFalse(VersionUtil.isAtLeast("1", "2"));
        }

        @Test
        void isAtLeast_differentPartLengths() {
            // 1.4 vs 1.4.0 — should treat missing as 0
            assertTrue(VersionUtil.isAtLeast("1.4", "1.4.0"));
        }

        @Test
        void wrapTimeline_emptyList() {
            String json = VersionUtil.wrapTimeline(GSON, List.of(), "1.4.0");
            assertNotNull(json);
            assertTrue(json.contains("\"createdBy\""));
            assertTrue(json.contains("\"timeline\""));
        }

        @Test
        void parseReplayJson_envelopeFormat() {
            List<TimelineEvent> original = List.of(new TimelineEvent.PlayerQuit(0, "u"));
            String json = VersionUtil.wrapTimeline(GSON, original, "1.4.0");

            List<TimelineEvent> parsed = VersionUtil.parseReplayJson(GSON, json, VersionUtil.MIN_RECORDING_VERSION, LIST_TYPE);
            assertEquals(1, parsed.size());
            assertInstanceOf(TimelineEvent.PlayerQuit.class, parsed.get(0));
        }

        @Test
        void parseReplayJson_legacyArrayFormat() {
            String json = "[{\"type\":\"player_quit\",\"tick\":0,\"uuid\":\"u\"}]";
            List<TimelineEvent> parsed = VersionUtil.parseReplayJson(GSON, json, "1.4.0", LIST_TYPE);
            assertEquals(1, parsed.size());
        }
    }

    // ── TimelineEventAdapter error paths ──────────────────────

    @Nested
    class AdapterErrors {

        @Test
        void unknownType_throwsJsonParseException() {
            String json = "{\"type\":\"totally_unknown\",\"tick\":0}";
            assertThrows(com.google.gson.JsonParseException.class,
                    () -> GSON.fromJson(json, TimelineEvent.class));
        }

        @Test
        void missingType_throwsJsonParseException() {
            String json = "{\"tick\":0,\"uuid\":\"u\"}";
            assertThrows(com.google.gson.JsonParseException.class,
                    () -> GSON.fromJson(json, TimelineEvent.class));
        }

        @Test
        void nullJson_returnsNull() {
            TimelineEvent event = GSON.fromJson("null", TimelineEvent.class);
            assertNull(event);
        }

        @Test
        void emptyObject_throwsJsonParseException() {
            String json = "{}";
            assertThrows(com.google.gson.JsonParseException.class,
                    () -> GSON.fromJson(json, TimelineEvent.class));
        }
    }
}
