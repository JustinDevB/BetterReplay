package me.justindevb.replay.storage.binary;

import me.justindevb.replay.chunk.CapturedChunkBaseline;
import me.justindevb.replay.chunk.ChunkRecordingArtifacts;
import me.justindevb.replay.chunk.ChunkRegionKey;
import me.justindevb.replay.chunk.ChunkTempRegionWriter;
import net.jpountz.lz4.LZ4FrameOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Writes captured chunk baselines into append-friendly temp-region files on disk.
 */
public final class BinaryChunkTempRegionFileWriter implements ChunkTempRegionWriter {

    public static final String TEMP_REGION_EXTENSION = ".brtmpregion";

    private final Path rootDirectory;
    private final BinaryChunkTempRegionFormat format = new BinaryChunkTempRegionFormat();
    private final Set<ChunkRegionKey> regionFiles = new HashSet<>();

    private int capturedChunkCount;

    public BinaryChunkTempRegionFileWriter(Path rootDirectory) throws IOException {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory");
        Files.createDirectories(rootDirectory);
    }

    @Override
    public void append(CapturedChunkBaseline baseline) throws IOException {
        Objects.requireNonNull(baseline, "baseline");
        ChunkRegionKey regionKey = baseline.coordinate().regionKey();
        Path regionPath = resolveRegionPath(regionKey);
        Files.createDirectories(regionPath.getParent());
        boolean writeHeader = Files.notExists(regionPath);

        byte[] compressedPayload = compress(baseline.payloadBytes());
        BinaryChunkTempRegionAppendRecord record = new BinaryChunkTempRegionAppendRecord(
                baseline.coordinate().localChunkX(),
                baseline.coordinate().localChunkZ(),
                baseline.payloadBytes().length,
                BinaryChunkCompression.LZ4_FRAME,
                compressedPayload);

        try (OutputStream outputStream = Files.newOutputStream(
                regionPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            if (writeHeader) {
                outputStream.write(format.headerBytes());
            }
            outputStream.write(format.encodeRecord(record));
        }

        regionFiles.add(regionKey);
        capturedChunkCount++;
    }

    @Override
    public ChunkRecordingArtifacts snapshotArtifacts() {
        return new ChunkRecordingArtifacts(rootDirectory, capturedChunkCount, regionFiles.size());
    }

    @Override
    public void close() {
        // no-op: each append opens and closes the file to keep crash recovery simple.
    }

    private Path resolveRegionPath(ChunkRegionKey regionKey) {
        String worldSegment = BinaryChunkArchiveNaming.worldDirectory(regionKey.worldName());
        return rootDirectory
                .resolve(worldSegment)
                .resolve("r." + regionKey.regionX() + "." + regionKey.regionZ() + TEMP_REGION_EXTENSION);
    }

    private static byte[] compress(byte[] payloadBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(out)) {
            lz4.write(payloadBytes);
        }
        return out.toByteArray();
    }
}