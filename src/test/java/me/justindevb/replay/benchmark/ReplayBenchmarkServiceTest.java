package me.justindevb.replay.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayBenchmarkServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void startPresetWritesMarkdownAndJsonReports() throws Exception {
        ReplayBenchmarkService service = new ReplayBenchmarkService(
                new ReplayBenchmarkHarness("1.4.0"),
                new ReplayBenchmarkReportWriter(tempDir),
                Runnable::run);

        ReplayBenchmarkArtifacts artifacts = service.startPreset(ReplayBenchmarkPreset.SMALL).join();

        assertEquals("small", artifacts.report().runs().getFirst().preset());
        assertTrue(Files.exists(artifacts.markdownPath()));
        assertTrue(Files.exists(artifacts.jsonPath()));
        assertTrue(Files.readString(artifacts.markdownPath()).contains("BetterReplay Benchmark Report"));
        assertTrue(Files.readString(artifacts.jsonPath()).contains("\"preset\": \"small\""));
        assertTrue(service.lastArtifacts().isPresent());
        assertFalse(service.isRunning());
    }
}