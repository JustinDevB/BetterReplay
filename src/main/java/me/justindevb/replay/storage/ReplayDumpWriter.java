package me.justindevb.replay.storage;

import me.justindevb.replay.debug.ReplayDumpQuery;
import me.justindevb.replay.recording.TimelineEvent;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class ReplayDumpWriter {

    private final File dumpDirectory;

    public ReplayDumpWriter(File dumpDirectory) {
        this.dumpDirectory = dumpDirectory;
    }

    public File writeDump(String replayName, List<TimelineEvent> timeline, ReplayDumpQuery query) throws IOException {
        ReplayDumpQuery effectiveQuery = query != null ? query : ReplayDumpQuery.all();
        File dumpFile = createDumpFile(replayName);

        List<String> lines = new ArrayList<>();
        lines.add("Replay dump: " + replayName);
        lines.add("Tick filter: " + describeTickRange(effectiveQuery));
        lines.add("");

        int startIndex = 0;
        if (effectiveQuery.hasTickRange() && timeline instanceof ReplayIndexedTimeline indexedTimeline) {
            startIndex = indexedTimeline.findEventIndexAtOrAfterTick(effectiveQuery.startTickOrDefault());
        }

        for (int index = startIndex; index < timeline.size(); index++) {
            TimelineEvent event = timeline.get(index);
            if (!effectiveQuery.includesTick(event.tick())) {
                if (effectiveQuery.endTick() != null && event.tick() > effectiveQuery.endTick()) {
                    break;
                }
                continue;
            }
            lines.add(formatEvent(event));
        }

        Files.write(dumpFile.toPath(), lines, StandardCharsets.UTF_8);
        return dumpFile;
    }

    private File createDumpFile(String replayName) throws IOException {
        Files.createDirectories(dumpDirectory.toPath());
        File dumpFile = File.createTempFile("replay_" + sanitizeFileName(replayName) + "_", ".txt", dumpDirectory);
        dumpFile.deleteOnExit();
        return dumpFile;
    }

    private static String sanitizeFileName(String replayName) {
        return replayName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String describeTickRange(ReplayDumpQuery query) {
        String start = query.startTick() != null ? String.valueOf(query.startTick()) : "all";
        String end = query.endTick() != null ? String.valueOf(query.endTick()) : "all";
        return start + ".." + end;
    }

    private static String formatEvent(TimelineEvent event) {
        StringBuilder builder = new StringBuilder();
        builder.append("[tick=").append(event.tick()).append("] ");
        builder.append(event.getClass().getSimpleName());

        for (RecordComponent component : event.getClass().getRecordComponents()) {
            if (component.getName().equals("tick")) {
                continue;
            }
            try {
                Object value = component.getAccessor().invoke(event);
                builder.append(' ').append(component.getName()).append('=').append(String.valueOf(value));
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Failed to format timeline event", ex);
            }
        }
        return builder.toString();
    }
}