package me.justindevb.replay.storage;

import me.justindevb.replay.api.ReplayExportQuery;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.binary.BinaryReplayArchiveFinalizer;
import me.justindevb.replay.storage.binary.BinaryReplayFormat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds filtered replay exports as finalized binary archives.
 */
public final class ReplayExporter {

    private final BinaryReplayArchiveFinalizer finalizer;
    private final File exportDirectory;

    public ReplayExporter() {
        this(new BinaryReplayArchiveFinalizer(), null);
    }

    public ReplayExporter(File exportDirectory) {
        this(new BinaryReplayArchiveFinalizer(), exportDirectory);
    }

    ReplayExporter(BinaryReplayArchiveFinalizer finalizer) {
        this(finalizer, null);
    }

    ReplayExporter(BinaryReplayArchiveFinalizer finalizer, File exportDirectory) {
        this.finalizer = finalizer;
        this.exportDirectory = exportDirectory;
    }

    public File exportReplay(String replayName, List<TimelineEvent> timeline, ReplayExportQuery query, String pluginVersion) throws IOException {
        ReplayExportQuery effectiveQuery = query != null ? query : ReplayExportQuery.all();
        Set<String> matchingPlayers = resolveMatchingPlayers(timeline, effectiveQuery);
        List<TimelineEvent> filtered = filterTimeline(timeline, effectiveQuery, matchingPlayers);

        File tempFile = createExportFile(replayName);
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(), finalizer.finalizeReplay(replayName, filtered, pluginVersion));
        return tempFile;
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
}