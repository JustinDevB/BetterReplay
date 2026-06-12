package me.justindevb.replay.util;

import me.justindevb.replay.storage.ReplaySummary;

import java.util.List;

public class ReplayCache {

    private static final long NEVER_REFRESHED = Long.MIN_VALUE;

    private List<String> replays = List.of();
    private List<ReplaySummary> replaySummaries = List.of();
    private long replaysCachedAtMillis = NEVER_REFRESHED;
    private long replaySummariesCachedAtMillis = NEVER_REFRESHED;

    public synchronized List<String> getReplays() {
        return replays;
    }

    public synchronized List<ReplaySummary> getReplaySummaries() {
        return replaySummaries;
    }

    public synchronized void setReplays(List<String> names) {
        replays = List.copyOf(names);
        replaysCachedAtMillis = System.currentTimeMillis();
        replaySummaries = List.of();
        replaySummariesCachedAtMillis = NEVER_REFRESHED;
    }

    public synchronized void setReplaySummaries(List<ReplaySummary> summaries) {
        replaySummaries = List.copyOf(summaries);
        replaySummariesCachedAtMillis = System.currentTimeMillis();
        replays = replaySummaries.stream()
                .map(ReplaySummary::name)
                .toList();
        replaysCachedAtMillis = replaySummariesCachedAtMillis;
    }

    public synchronized boolean hasFreshReplays(long maxAgeMillis) {
        return isFresh(replaysCachedAtMillis, maxAgeMillis);
    }

    public synchronized boolean hasFreshReplaySummaries(long maxAgeMillis) {
        return isFresh(replaySummariesCachedAtMillis, maxAgeMillis);
    }

    private boolean isFresh(long cachedAtMillis, long maxAgeMillis) {
        if (cachedAtMillis == NEVER_REFRESHED) {
            return false;
        }
        return System.currentTimeMillis() - cachedAtMillis <= maxAgeMillis;
    }
}


