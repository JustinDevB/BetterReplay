package me.justindevb.replay.storage;

/**
 * Optional capability for replay timelines that can seek by tick efficiently.
 */
public interface ReplayIndexedTimeline {

    int findEventIndexAtOrAfterTick(int targetTick);
}