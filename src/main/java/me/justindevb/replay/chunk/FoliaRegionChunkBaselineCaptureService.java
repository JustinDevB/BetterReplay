package me.justindevb.replay.chunk;

import com.tcoded.folialib.FoliaLib;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Runs live chunk reads on the owning Folia region thread before encoding baselines.
 */
public final class FoliaRegionChunkBaselineCaptureService implements ChunkBaselineCaptureService {

    @FunctionalInterface
    interface RegionTaskScheduler {
        CompletableFuture<Void> schedule(Location location, Runnable task);
    }

    @FunctionalInterface
    interface RegionOwnershipChecker {
        boolean isOwnedByCurrentRegion(World world, ChunkCoordinate coordinate);
    }

    private final ChunkBaselineCaptureService delegate;
    private final RegionTaskScheduler regionTaskScheduler;
    private final RegionOwnershipChecker regionOwnershipChecker;

    public FoliaRegionChunkBaselineCaptureService(FoliaLib foliaLib, ChunkBaselineCaptureService delegate) {
        this(
                delegate,
                regionTaskScheduler(foliaLib),
                regionOwnershipChecker(foliaLib));
    }

    FoliaRegionChunkBaselineCaptureService(
            ChunkBaselineCaptureService delegate,
            RegionTaskScheduler regionTaskScheduler,
            RegionOwnershipChecker regionOwnershipChecker
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.regionTaskScheduler = Objects.requireNonNull(regionTaskScheduler, "regionTaskScheduler");
        this.regionOwnershipChecker = Objects.requireNonNull(regionOwnershipChecker, "regionOwnershipChecker");
    }

    @Override
    public CapturedChunkBaseline capture(ChunkCoordinate coordinate) throws IOException {
        World world = Bukkit.getWorld(coordinate.worldName());
        if (world == null) {
            throw new IOException("World is not available for chunk capture: " + coordinate.worldName());
        }

        if (regionOwnershipChecker.isOwnedByCurrentRegion(world, coordinate)) {
            return delegate.capture(coordinate);
        }

        CompletableFuture<CapturedChunkBaseline> captureFuture = new CompletableFuture<>();
        CompletableFuture<Void> scheduledTask;
        try {
            scheduledTask = regionTaskScheduler.schedule(chunkTaskLocation(world, coordinate), () -> {
                try {
                    captureFuture.complete(delegate.capture(coordinate));
                } catch (IOException | RuntimeException ex) {
                    captureFuture.completeExceptionally(ex);
                } catch (Error error) {
                    captureFuture.completeExceptionally(error);
                    throw error;
                }
            });
        } catch (RuntimeException ex) {
            throw new IOException("Failed to schedule chunk capture for " + coordinate, ex);
        }

        scheduledTask.whenComplete((ignored, ex) -> {
            if (ex != null) {
                captureFuture.completeExceptionally(ex);
            }
        });
        return awaitCapture(coordinate, captureFuture);
    }

    private static Location chunkTaskLocation(World world, ChunkCoordinate coordinate) {
        return new Location(world, coordinate.chunkX() << 4, 0, coordinate.chunkZ() << 4);
    }

    private static RegionTaskScheduler regionTaskScheduler(FoliaLib foliaLib) {
        Objects.requireNonNull(foliaLib, "foliaLib");
        return (location, task) -> foliaLib.getScheduler().runAtLocation(location, ignored -> task.run());
    }

    private static RegionOwnershipChecker regionOwnershipChecker(FoliaLib foliaLib) {
        Objects.requireNonNull(foliaLib, "foliaLib");
        return (world, coordinate) -> foliaLib.getScheduler().isOwnedByCurrentRegion(
                world,
                coordinate.chunkX(),
                coordinate.chunkZ());
    }

    private static CapturedChunkBaseline awaitCapture(
            ChunkCoordinate coordinate,
            CompletableFuture<CapturedChunkBaseline> captureFuture
    ) throws IOException {
        try {
            return captureFuture.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while capturing chunk baseline for " + coordinate, ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Failed to capture chunk baseline for " + coordinate, cause);
        }
    }
}
