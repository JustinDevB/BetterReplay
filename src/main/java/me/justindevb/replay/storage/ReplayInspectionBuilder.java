package me.justindevb.replay.storage;

import me.justindevb.replay.recording.TimelineEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ReplayInspectionBuilder {

    private ReplayInspectionBuilder() {
    }

    public static ReplayInspection build(
            String replayName,
            ReplayFormat format,
            long storedBytes,
            long compressedPayloadBytes,
            long decompressedPayloadBytes,
            Long recordingStartedAtEpochMillis,
            String recordedWithVersion,
            String minimumViewerVersion,
            boolean indexedPayload,
            int seekCheckpointCount,
            List<TimelineEvent> timeline
    ) {
        int recordCount = timeline.size();
        int startTick = recordCount == 0 ? 0 : Integer.MAX_VALUE;
        int endTick = 0;
        Set<String> actors = new HashSet<>();
        Set<String> worlds = new HashSet<>();

        for (TimelineEvent event : timeline) {
            if (recordCount != 0) {
                startTick = Math.min(startTick, event.tick());
                endTick = Math.max(endTick, event.tick());
            }
            if (event.uuid() != null && !event.uuid().isBlank()) {
                actors.add(event.uuid());
            }

            String world = extractWorld(event);
            if (world != null && !world.isBlank()) {
                worlds.add(world);
            }
        }

        int durationTicks = recordCount == 0 ? 0 : Math.max(0, endTick - startTick);
        return new ReplayInspection(
                replayName,
                format,
                recordCount,
                recordCount == 0 ? 0 : startTick,
                endTick,
                durationTicks,
                durationTicks / 20.0,
                storedBytes,
                compressedPayloadBytes,
                decompressedPayloadBytes,
                recordingStartedAtEpochMillis,
                recordedWithVersion,
                minimumViewerVersion,
                actors.size(),
                worlds.size(),
                indexedPayload,
                seekCheckpointCount);
    }

    private static String extractWorld(TimelineEvent event) {
        if (event instanceof TimelineEvent.PlayerMove playerMove) {
            return playerMove.world();
        }
        if (event instanceof TimelineEvent.EntityMove entityMove) {
            return entityMove.world();
        }
        if (event instanceof TimelineEvent.BlockBreak blockBreak) {
            return blockBreak.world();
        }
        if (event instanceof TimelineEvent.BlockBreakComplete blockBreakComplete) {
            return blockBreakComplete.world();
        }
        if (event instanceof TimelineEvent.BlockBreakStage blockBreakStage) {
            return blockBreakStage.world();
        }
        if (event instanceof TimelineEvent.BlockPlace blockPlace) {
            return blockPlace.world();
        }
        if (event instanceof TimelineEvent.ItemDrop itemDrop) {
            return itemDrop.locWorld();
        }
        if (event instanceof TimelineEvent.EntitySpawn entitySpawn) {
            return entitySpawn.world();
        }
        if (event instanceof TimelineEvent.EntityDeath entityDeath) {
            return entityDeath.world();
        }
        return null;
    }
}