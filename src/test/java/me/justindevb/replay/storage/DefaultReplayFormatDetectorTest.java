package me.justindevb.replay.storage;

import me.justindevb.replay.storage.binary.BinaryReplayStorageCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultReplayFormatDetectorTest {

    private final JsonReplayStorageCodec jsonCodec = new JsonReplayStorageCodec();
    private final BinaryReplayStorageCodec binaryCodec = new BinaryReplayStorageCodec();

    @Test
    void returnsFirstCodecThatRecognizesPayload() {
        DefaultReplayFormatDetector detector = new DefaultReplayFormatDetector(List.of(jsonCodec));
        byte[] payload = "[]".getBytes(StandardCharsets.UTF_8);

        ReplayStorageCodec detected = detector.detectCodec("test.json", payload);

        assertSame(jsonCodec, detected);
    }

    @Test
    void rejectsUnknownPayloads() {
        DefaultReplayFormatDetector detector = new DefaultReplayFormatDetector(List.of(jsonCodec));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> detector.detectCodec("unknown.bin", new byte[] {0x00, 0x01, 0x02}));

        assertTrue(ex.getMessage().contains("No replay storage codec"));
    }

    @Test
    void detectsBinaryReplayArchives() throws Exception {
        DefaultReplayFormatDetector detector = new DefaultReplayFormatDetector(List.of(jsonCodec, binaryCodec));
        byte[] payload = binaryCodec.finalizeReplay("test", List.of(new me.justindevb.replay.recording.TimelineEvent.PlayerQuit(0, "uuid-1")), "1.4.0");

        ReplayStorageCodec detected = detector.detectCodec("test.br", payload);

        assertSame(binaryCodec, detected);
    }
}