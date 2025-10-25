package me.justindevb.replay;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ReplayRegistry {
    private static final Set<ReplaySession> activeSessions = ConcurrentHashMap.newKeySet();

    public static void add(ReplaySession session) {
        activeSessions.add(session);
    }

    public static void remove(ReplaySession session) {
        activeSessions.remove(session);
    }

    public static boolean contains(ReplaySession session) {
        return activeSessions.contains(session);
    }

    public static RecordedEntity getEntityById(int id) {
        for (ReplaySession session : activeSessions) {
            RecordedEntity e = session.getRecordedEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    public static Set<ReplaySession> getActiveSessions() {
        return activeSessions;
    }
}
