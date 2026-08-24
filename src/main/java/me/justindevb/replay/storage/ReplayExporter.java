package me.justindevb.replay.storage;

import me.justindevb.replay.api.ReplayExportQuery;
import me.justindevb.replay.chunk.ChunkCoordinate;
import me.justindevb.replay.chunk.ReplayChunkData;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.binary.BinaryChunkArchiveNaming;
import me.justindevb.replay.storage.binary.BinaryChunkRegionCodec;
import me.justindevb.replay.storage.binary.BinaryChunkRegionEntry;
import me.justindevb.replay.storage.binary.BinaryReplayChunkMetadata;
import me.justindevb.replay.storage.binary.BinaryReplayArchiveFinalizer;
import me.justindevb.replay.storage.binary.BinaryReplayFormat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;

/**
 * Builds filtered replay exports as finalized binary archives.
 */
public final class ReplayExporter {

    private static final Comparator<BinaryChunkRegionEntry> CHUNK_REGION_ENTRY_ORDER = Comparator
            .comparingInt(BinaryChunkRegionEntry::localChunkX)
            .thenComparingInt(BinaryChunkRegionEntry::localChunkZ);
    private static final Pattern CHUNK_REGION_ENTRY_NAME = Pattern.compile(
            Pattern.quote(BinaryReplayFormat.RESERVED_CHUNKS_PREFIX)
                    + "([^/]+)/r\\.(-?\\d+)\\.(-?\\d+)"
                    + Pattern.quote(BinaryReplayFormat.CHUNK_REGION_FILE_EXTENSION));

    private final BinaryReplayArchiveFinalizer finalizer;
    private final BinaryChunkRegionCodec chunkRegionCodec;
    private final File exportDirectory;

    public ReplayExporter() {
        this(new BinaryReplayArchiveFinalizer(), null);
    }

    public ReplayExporter(File exportDirectory) {
        this(new BinaryReplayArchiveFinalizer(), exportDirectory);
    }

    ReplayExporter(BinaryReplayArchiveFinalizer finalizer) {
        this(finalizer, new BinaryChunkRegionCodec(), null);
    }

    ReplayExporter(BinaryReplayArchiveFinalizer finalizer, File exportDirectory) {
        this(finalizer, new BinaryChunkRegionCodec(), exportDirectory);
    }

    ReplayExporter(BinaryReplayArchiveFinalizer finalizer, BinaryChunkRegionCodec chunkRegionCodec, File exportDirectory) {
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
        this.chunkRegionCodec = Objects.requireNonNull(chunkRegionCodec, "chunkRegionCodec");
        this.exportDirectory = exportDirectory;
    }

    public File exportReplay(String replayName, List<TimelineEvent> timeline, ReplayExportQuery query, String pluginVersion) throws IOException {
        return exportReplay(replayName, new ReplayPlaybackData(timeline), query, pluginVersion);
    }

    public File exportReplay(String replayName, ReplayPlaybackData replayData, ReplayExportQuery query, String pluginVersion) throws IOException {
        ReplayExportQuery effectiveQuery = query != null ? query : ReplayExportQuery.all();
        List<TimelineEvent> timeline = replayData.timeline();
        Set<String> matchingPlayers = resolveMatchingPlayers(timeline, effectiveQuery);
        List<TimelineEvent> filtered = filterTimeline(timeline, effectiveQuery, matchingPlayers);
        ReplayChunkData filteredChunkData = filterChunkData(replayData.chunkData(), effectiveQuery, timeline, matchingPlayers);

        File tempFile = createExportFile(replayName);
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(), finalizer.finalizeReplay(replayName, filtered, pluginVersion, System.currentTimeMillis(), filteredChunkData));
        return tempFile;
    }

    private ReplayChunkData filterChunkData(
            ReplayChunkData chunkData,
            ReplayExportQuery query,
            List<TimelineEvent> timeline,
            Set<String> matchingPlayers
    ) throws IOException {
        if (!chunkData.hasChunkData()) {
            return ReplayChunkData.NONE;
        }
        if (query.isAllPlayers()) {
            return chunkData;
        }

        PlayerChunkPositions playerChunkPositions = collectPlayerChunkPositions(timeline, query, matchingPlayers);
        if (playerChunkPositions.included().isEmpty()) {
            return ReplayChunkData.NONE;
        }

        Map<String, byte[]> filteredRegionEntries = new LinkedHashMap<>();
        int chunkEntryCount = 0;
        List<String> coordinateDigests = new ArrayList<>();
        for (String entryName : chunkData.regionEntries().keySet().stream().sorted().toList()) {
            byte[] regionBytes = chunkData.regionEntries().get(entryName);
            ChunkRegionEntryName parsedEntryName = parseChunkRegionEntryName(entryName);
            if (parsedEntryName == null) {
                continue;
            }

            List<BinaryChunkRegionEntry> selectedEntries = chunkRegionCodec.decode(regionBytes).entries().stream()
                    .filter(entry -> shouldIncludeChunk(parsedEntryName, entry, playerChunkPositions))
                    .sorted(CHUNK_REGION_ENTRY_ORDER)
                    .toList();
            if (selectedEntries.isEmpty()) {
                continue;
            }

            filteredRegionEntries.put(entryName, chunkRegionCodec.encode(selectedEntries));
            chunkEntryCount += selectedEntries.size();
            for (BinaryChunkRegionEntry entry : selectedEntries) {
                coordinateDigests.add(entryName + ':' + entry.localChunkX() + ':' + entry.localChunkZ());
            }
        }

        if (filteredRegionEntries.isEmpty()) {
            return ReplayChunkData.NONE;
        }

        BinaryReplayChunkMetadata metadata = BinaryReplayChunkMetadata.present(
                filteredRegionEntries.size(),
                chunkEntryCount,
                crc32cHex(coordinateDigests),
                chunkData.metadata().payloadFormat());
        return new ReplayChunkData(metadata, filteredRegionEntries);
    }

    private static PlayerChunkPositions collectPlayerChunkPositions(
            List<TimelineEvent> timeline,
            ReplayExportQuery query,
            Set<String> matchingPlayers
    ) {
        Map<String, Set<ChunkPosition>> included = new LinkedHashMap<>();
        Map<String, Set<ChunkPosition>> excluded = new LinkedHashMap<>();
        for (TimelineEvent event : timeline) {
            if (!(event instanceof TimelineEvent.PlayerMove playerMove)
                    || playerMove.world() == null
                    || playerMove.uuid() == null
                    || !query.includesTick(playerMove.tick())) {
                continue;
            }

            ChunkCoordinate coordinate = new ChunkCoordinate(
                    playerMove.world(),
                    Math.floorDiv((int) Math.floor(playerMove.x()), 16),
                    Math.floorDiv((int) Math.floor(playerMove.z()), 16));
            Map<String, Set<ChunkPosition>> target = matchingPlayers.contains(playerMove.uuid().toLowerCase(Locale.ROOT))
                    ? included
                    : excluded;
            target.computeIfAbsent(BinaryChunkArchiveNaming.worldDirectory(coordinate.worldName()), ignored -> new HashSet<>())
                    .add(new ChunkPosition(coordinate.chunkX(), coordinate.chunkZ()));
        }
        return new PlayerChunkPositions(included, excluded);
    }

    private static boolean shouldIncludeChunk(
            ChunkRegionEntryName entryName,
            BinaryChunkRegionEntry entry,
            PlayerChunkPositions playerChunkPositions
    ) {
        Set<ChunkPosition> included = playerChunkPositions.included().get(entryName.worldDirectory());
        if (included == null || included.isEmpty()) {
            return false;
        }

        int chunkX = entryName.regionX() * 32 + entry.localChunkX();
        int chunkZ = entryName.regionZ() * 32 + entry.localChunkZ();
        int includedDistance = nearestChebyshevDistance(chunkX, chunkZ, included);
        Set<ChunkPosition> excluded = playerChunkPositions.excluded().get(entryName.worldDirectory());
        if (excluded == null || excluded.isEmpty()) {
            return true;
        }
        return includedDistance <= nearestChebyshevDistance(chunkX, chunkZ, excluded);
    }

    private static int nearestChebyshevDistance(int chunkX, int chunkZ, Set<ChunkPosition> positions) {
        int nearest = Integer.MAX_VALUE;
        for (ChunkPosition position : positions) {
            int distance = Math.max(Math.abs(chunkX - position.chunkX()), Math.abs(chunkZ - position.chunkZ()));
            if (distance < nearest) {
                nearest = distance;
            }
        }
        return nearest;
    }

    private static ChunkRegionEntryName parseChunkRegionEntryName(String entryName) {
        Matcher matcher = CHUNK_REGION_ENTRY_NAME.matcher(entryName);
        if (!matcher.matches()) {
            return null;
        }
        return new ChunkRegionEntryName(
                matcher.group(1),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
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

    private File createExportFile(String replayName) throws IOException {
        if (exportDirectory == null) {
            return File.createTempFile("replay_" + replayName + "_", BinaryReplayFormat.FILE_EXTENSION);
        }

        Files.createDirectories(exportDirectory.toPath());
        return File.createTempFile("replay_" + replayName + "_", BinaryReplayFormat.FILE_EXTENSION, exportDirectory);
    }

    private static List<TimelineEvent> filterTimeline(List<TimelineEvent> timeline, ReplayExportQuery query, Set<String> matchingPlayers) {
        int startIndex = 0;
        if (query.hasTickRange() && timeline instanceof ReplayIndexedTimeline indexedTimeline) {
            startIndex = indexedTimeline.findEventIndexAtOrAfterTick(query.startTickOrDefault());
        }

        List<TimelineEvent> filtered = new ArrayList<>();
        for (int index = startIndex; index < timeline.size(); index++) {
            TimelineEvent event = timeline.get(index);
            if (!query.includesTick(event.tick())) {
                if (query.endTick() != null && event.tick() > query.endTick()) {
                    break;
                }
                continue;
            }
            if (!matchingPlayers.isEmpty() && (event.uuid() == null || !matchingPlayers.contains(event.uuid().toLowerCase(Locale.ROOT)))) {
                continue;
            }
            filtered.add(event);
        }
        return filtered;
    }

    private static Set<String> resolveMatchingPlayers(List<TimelineEvent> timeline, ReplayExportQuery query) {
        if (query.isAllPlayers()) {
            return Set.of();
        }

        String needle = query.player().toLowerCase(Locale.ROOT);
        Set<String> matchingPlayers = new HashSet<>();
        for (int index = 0; index < timeline.size(); index++) {
            TimelineEvent event = timeline.get(index);
            if (event.uuid() != null && event.uuid().equalsIgnoreCase(needle)) {
                matchingPlayers.add(event.uuid().toLowerCase(Locale.ROOT));
            }
            if (event instanceof TimelineEvent.PlayerMove playerMove
                    && playerMove.name() != null
                    && playerMove.name().equalsIgnoreCase(needle)
                    && playerMove.uuid() != null) {
                matchingPlayers.add(playerMove.uuid().toLowerCase(Locale.ROOT));
            }
        }
        return matchingPlayers;
    }

    private record PlayerChunkPositions(Map<String, Set<ChunkPosition>> included, Map<String, Set<ChunkPosition>> excluded) {
    }

    private record ChunkPosition(int chunkX, int chunkZ) {
    }

    private record ChunkRegionEntryName(String worldDirectory, int regionX, int regionZ) {
    }
}
