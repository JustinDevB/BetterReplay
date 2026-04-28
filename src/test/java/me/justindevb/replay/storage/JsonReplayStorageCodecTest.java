package me.justindevb.replay.storage;

import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.util.VersionUtil;
import me.justindevb.replay.util.io.ReplayCompressor;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonReplayStorageCodecTest {

    private final JsonReplayStorageCodec codec = new JsonReplayStorageCodec();

    private List<TimelineEvent> sampleTimeline() {
        return List.of(
                new TimelineEvent.PlayerMove(0, "uuid-1", "Steve", "world", 1, 64, 3, 0, 0, "STANDING"),
                new TimelineEvent.PlayerQuit(10, "uuid-1")
        );
    }

    @Test
    void encodesAndDecodesCurrentEnvelopeFormat() throws Exception {
        byte[] payload = codec.encodeTimeline(sampleTimeline(), "1.4.0");

        List<TimelineEvent> decoded = codec.decodeTimeline(payload, "1.4.0");

        assertEquals(ReplayFormat.JSON, codec.format());
        assertEquals(2, decoded.size());
        assertInstanceOf(TimelineEvent.PlayerMove.class, decoded.get(0));
        assertInstanceOf(TimelineEvent.PlayerQuit.class, decoded.get(1));
    }

    @Test
    void decodesCompressedJsonPayloads() throws Exception {
        byte[] payload = codec.encodeTimeline(sampleTimeline(), "1.4.0");
        byte[] compressed = ReplayCompressor.compress(new String(payload, java.nio.charset.StandardCharsets.UTF_8));

        List<TimelineEvent> decoded = codec.decodeTimeline(compressed, "1.4.0");

        assertEquals(2, decoded.size());
    }

    @Test
    void detectsLegacyRawArrayPayloads() throws Exception {
        byte[] legacyJson = "[{\"type\":\"player_quit\",\"tick\":0,\"uuid\":\"u\"}]"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(codec.canDecode("legacy.json", legacyJson));
        List<TimelineEvent> decoded = codec.decodeTimeline(legacyJson, "1.4.0");
        assertEquals(1, decoded.size());
        assertInstanceOf(TimelineEvent.PlayerQuit.class, decoded.get(0));
    }

    @Test
    void rejectsNewerEnvelopeVersionsWhenDecoding() {
        byte[] payload = ("{\"createdBy\":\"1.5.0\",\"minVersion\":\"9.0.0\",\"timeline\":[]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThrows(VersionUtil.ReplayVersionMismatchException.class,
                () -> codec.decodeTimeline(payload, "1.4.0"));
    }

    @Test
    void writesPortableJsonReplayFile() throws Exception {
        File file = codec.writeReplayFile("codec-test", codec.encodeTimeline(sampleTimeline(), "1.4.0"), "1.4.0");

        assertNotNull(file);
        assertTrue(file.exists());
        assertTrue(file.getName().endsWith(".json"));
        String json = Files.readString(file.toPath());
        assertTrue(json.contains("player_move"));
        assertTrue(json.contains("player_quit"));
    }
}