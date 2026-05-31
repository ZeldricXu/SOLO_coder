package com.datamasker.domain.mpc.monitor;

import com.datamasker.domain.mpc.model.MpcSession;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MpcStatusExposer {

    private final Map<String, MpcSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, List<String>> sessionTimelines = new ConcurrentHashMap<>();

    public SessionSnapshot getSessionStatus(String sessionId) {
        MpcSession session = activeSessions.get(sessionId);
        if (session == null) {
            return null;
        }
        return createSnapshot(session);
    }

    public List<SessionSnapshot> getAllActiveSessions() {
        return activeSessions.values().stream()
                .map(this::createSnapshot)
                .toList();
    }

    public List<String> getSessionTimeline(String sessionId) {
        return sessionTimelines.getOrDefault(sessionId, new ArrayList<>());
    }

    public void registerSession(MpcSession session) {
        activeSessions.put(session.getSessionId(), session);
        addTimelineEvent(session.getSessionId(), "CREATED at " + LocalDateTime.now());
    }

    public void updateSessionStatus(MpcSession session, String status) {
        activeSessions.put(session.getSessionId(), session);
        addTimelineEvent(session.getSessionId(), "STATUS_CHANGE: " + status + " at " + LocalDateTime.now());
    }

    public void removeSession(String sessionId) {
        activeSessions.remove(sessionId);
    }

    private void addTimelineEvent(String sessionId, String event) {
        sessionTimelines.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(event);
    }

    private SessionSnapshot createSnapshot(MpcSession session) {
        SessionSnapshot snapshot = new SessionSnapshot();
        snapshot.setSessionId(session.getSessionId());
        snapshot.setStatus(session.getStatus());
        snapshot.setPartyCount(session.getPartyCount());
        snapshot.setProtocol(session.getProtocolType());
        snapshot.setCreatedAt(session.getCreatedAt());
        snapshot.setElapsedMs(ChronoUnit.MILLIS.between(session.getCreatedAt(), LocalDateTime.now()));
        snapshot.setInputsSubmitted(session.getParties() != null ? session.getParties().size() : 0);
        return snapshot;
    }

    @Data
    public static class SessionSnapshot {
        private String sessionId;
        private String status;
        private int partyCount;
        private String protocol;
        private LocalDateTime createdAt;
        private long elapsedMs;
        private int inputsSubmitted;
    }
}
