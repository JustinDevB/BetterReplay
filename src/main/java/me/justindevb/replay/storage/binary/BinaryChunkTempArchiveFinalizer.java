package me.justindevb.replay.storage.binary;

import me.justindevb.replay.chunk.ChunkArchiveFinalizer;
import me.justindevb.replay.chunk.ChunkRecordingArtifacts;
import me.justindevb.replay.chunk.ReplayChunkData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

/**
 * Finalizes temp chunk region files into archive-ready .brregion entries.
 */
public final class BinaryChunkTempArchiveFinalizer implements ChunkArchiveFinalizer {

    private static final Comparator<BinaryChunkRegionEntry> REGION_ENTRY_ORDER = Comparator
            .comparingInt(BinaryChunkRegionEntry::localChunkX)
            .thenComparingInt(BinaryChunkRegionEntry::localChunkZ);
    private static final Pattern TEMP_REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)" + Pattern.quote(BinaryChunkTempRegionFileWriter.TEMP_REGION_EXTENSION));

    private final BinaryChunkTempRegionFormat tempRegionFormat;
    private final BinaryChunkRegionCodec regionCodec;

    public BinaryChunkTempArchiveFinalizer() {
        this(new BinaryChunkTempRegionFormat(), new BinaryChunkRegionCodec());
    }

    BinaryChunkTempArchiveFinalizer(BinaryChunkTempRegionFormat tempRegionFormat, BinaryChunkRegionCodec regionCodec) {
        this.tempRegionFormat = Objects.requireNonNull(tempRegionFormat, "tempRegionFormat");
        this.regionCodec = Objects.requireNonNull(regionCodec, "regionCodec");
    }

    @Override
    public ReplayChunkData finalizeArtifacts(ChunkRecordingArtifacts artifacts) throws IOException {
        if (artifacts == null || !artifacts.isPresent() || !Files.isDirectory(artifacts.rootDirectory())) {
            return ReplayChunkData.NONE;
        }

        List<Path> tempRegionFiles;
        try (Stream<Path> walk = Files.walk(artifacts.rootDirectory())) {
            tempRegionFiles = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(BinaryChunkTempRegionFileWriter.TEMP_REGION_EXTENSION))
                    .sorted()
                    .toList();
        }

        if (tempRegionFiles.isEmpty()) {
            return ReplayChunkData.NONE;
        }

        Map<String, byte[]> regionEntries = new LinkedHashMap<>();
        List<String> coordinateDigests = new ArrayList<>();
        int chunkEntryCount = 0;

        for (Path tempRegionFile : tempRegionFiles) {
            FinalizedRegion finalizedRegion = finalizeRegionFile(artifacts.rootDirectory(), tempRegionFile);
            if (finalizedRegion.entries().isEmpty()) {
                continue;
            }

            regionEntries.put(finalizedRegion.entryName(), regionCodec.encode(finalizedRegion.entries()));
            chunkEntryCount += finalizedRegion.entries().size();
            for (BinaryChunkRegionEntry entry : finalizedRegion.entries()) {
                coordinateDigests.add(finalizedRegion.entryName() + ':' + entry.localChunkX() + ':' + entry.localChunkZ());
            }
        }

        if (regionEntries.isEmpty()) {
            return ReplayChunkData.NONE;
        }

        BinaryReplayChunkMetadata metadata = BinaryReplayChunkMetadata.present(
                regionEntries.size(),
                chunkEntryCount,
            crc32cHex(coordinateDigests),
            artifacts.chunkPayloadFormat());
        return new ReplayChunkData(metadata, regionEntries);
    }

    private FinalizedRegion finalizeRegionFile(Path rootDirectory, Path tempRegionFile) throws IOException {
        Path relativePath = rootDirectory.relativize(tempRegionFile);
        if (relativePath.getNameCount() < 2) {
            throw new IOException("Chunk temp region path is missing a world directory: " + relativePath);
        }
        String worldDirectory = relativePath.getName(0).toString();
        Matcher matcher = TEMP_REGION_NAME.matcher(tempRegionFile.getFileName().toString());
        if (!matcher.matches()) {
            throw new IOException("Chunk temp region file name is invalid: " + tempRegionFile.getFileName());
        }

        int regionX = Integer.parseInt(matcher.group(1));
        int regionZ = Integer.parseInt(matcher.group(2));
        byte[] bytes = Files.readAllBytes(tempRegionFile);
        if (bytes.length < BinaryReplayFormat.CHUNK_TEMP_REGION_HEADER_SIZE) {
            throw new IOException("Chunk temp region file is too short: " + tempRegionFile);
        }
        tempRegionFormat.validateHeader(bytes);

        Map<Integer, BinaryChunkRegionEntry> latestEntries = new LinkedHashMap<>();
        int offset = BinaryReplayFormat.CHUNK_TEMP_REGION_HEADER_SIZE;
        while (offset < bytes.length) {
            BinaryChunkTempRegionFormat.DecodedAppendRecord decoded = tempRegionFormat.decodeRecord(bytes, offset);
            BinaryChunkTempRegionAppendRecord record = decoded.record();
            latestEntries.put(
                    (record.localChunkX() << 8) | record.localChunkZ(),
                    new BinaryChunkRegionEntry(
                            record.localChunkX(),
                            record.localChunkZ(),
                            record.uncompressedLength(),
                            record.compression(),
                            record.compressedPayload()));
            offset = decoded.nextOffset();
        }

        List<BinaryChunkRegionEntry> entries = latestEntries.values().stream()
                .sorted(REGION_ENTRY_ORDER)
                .toList();
        String entryName = BinaryReplayFormat.RESERVED_CHUNKS_PREFIX
                + worldDirectory
                + "/r."
                + regionX
                + "."
                + regionZ
                + BinaryReplayFormat.CHUNK_REGION_FILE_EXTENSION;
        return new FinalizedRegion(entryName, entries);
    }

    private static String crc32cHex(List<String> coordinateDigests) {
        CRC32C crc32c = new CRC32C();
        for (String digest : coordinateDigests) {
            byte[] bytes = digest.getBytes(BinaryReplayFormat.STRING_CHARSET);
            crc32c.update(bytes, 0, bytes.length);
            crc32c.update('\n');
        }
        return "%08x".formatted(crc32c.getValue());
    }

    private record FinalizedRegion(String entryName, List<BinaryChunkRegionEntry> entries) {
    }
}