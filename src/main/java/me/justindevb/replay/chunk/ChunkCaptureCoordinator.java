package me.justindevb.replay.chunk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Coordinates periodic chunk discovery, deduplication, and bounded capture.
 */
public final class ChunkCaptureCoordinator implements AutoCloseable {

    private static final Comparator<ChunkCoordinate> CHUNK_ORDER = Comparator
            .comparing(ChunkCoordinate::worldName)
            .thenComparingInt(ChunkCoordinate::chunkX)
            .thenComparingInt(ChunkCoordinate::chunkZ);

    private final ChunkCaptureConfig config;
    private final ChunkInterestTracker interestTracker;
    private final ChunkBaselineCaptureService baselineCaptureService;
    private final ChunkTempRegionWriter tempRegionWriter;
    private final Set<ChunkCoordinate> capturedChunks = new HashSet<>();

    private boolean truncated;

    public ChunkCaptureCoordinator(
            ChunkCaptureConfig config,
            ChunkInterestTracker interestTracker,
            ChunkBaselineCaptureService baselineCaptureService,
            ChunkTempRegionWriter tempRegionWriter
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.interestTracker = Objects.requireNonNull(interestTracker, "interestTracker");
        this.baselineCaptureService = Objects.requireNonNull(baselineCaptureService, "baselineCaptureService");
        this.tempRegionWriter = Objects.requireNonNull(tempRegionWriter, "tempRegionWriter");
    }

    public CaptureResult captureTrackedChunks(Collection<ChunkCoordinate> trackedPlayerChunks) throws IOException {
        Set<ChunkCoordinate> discovered = interestTracker.discoverChunks(trackedPlayerChunks, config.radius());
        if (discovered.isEmpty()) {
            return new CaptureResult(0, capturedChunks.size(), truncated);
        }

        List<ChunkCoordinate> pending = new ArrayList<>();
        for (ChunkCoordinate coordinate : discovered) {
            if (!capturedChunks.contains(coordinate)) {
                pending.add(coordinate);
            }
        }
        pending.sort(CHUNK_ORDER);

        int remainingCapacity = Math.max(0, config.maxUniqueChunksPerRecording() - capturedChunks.size());
        int capturedThisPass = 0;
        for (ChunkCoordinate coordinate : pending) {
            if (capturedThisPass >= remainingCapacity) {
                truncated = true;
                break;
            }

            tempRegionWriter.append(baselineCaptureService.capture(coordinate));
            capturedChunks.add(coordinate);
            capturedThisPass++;
        }

        if (pending.size() > capturedThisPass) {
            truncated = true;
        }

        return new CaptureResult(capturedThisPass, capturedChunks.size(), truncated);
    }

    public ChunkRecordingArtifacts snapshotArtifacts() {
        return tempRegionWriter.snapshotArtifacts();
    }

    @Override
    public void close() throws IOException {
        tempRegionWriter.close();
    }

    public record CaptureResult(int capturedThisPass, int totalCaptured, boolean truncated) {
    }
}