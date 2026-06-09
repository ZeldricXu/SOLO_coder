package com.loganalytics.detector.anomaly;

import com.loganalytics.common.model.AnomalyEvent;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.common.model.LogPattern;
import com.loganalytics.common.util.IdUtils;
import com.loganalytics.detector.config.DetectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ContentAnomalyDetector {
    private static final Logger log = LoggerFactory.getLogger(ContentAnomalyDetector.class);

    private final DetectorConfig config;
    private final Set<String> seenPatterns;
    private final Map<String, Long> patternFirstSeen;
    private final Map<String, Long> lastAlertTime;
    private final Set<String> criticalKeywords;

    public ContentAnomalyDetector(DetectorConfig config) {
        this.config = config;
        this.seenPatterns = ConcurrentHashMap.newKeySet();
        this.patternFirstSeen = new ConcurrentHashMap<>();
        this.lastAlertTime = new ConcurrentHashMap<>();
        this.criticalKeywords = Set.of(
                "out of memory", "oom", "stack overflow", "nullpointerexception",
                "classnotfound", "noclassdeffound", "fatal", "panic",
                "connection refused", "connection reset", "timeout",
                "deadlock", "disk full", "out of disk"
        );
    }

    public List<AnomalyEvent> detect(List<LogPattern> patterns, List<LogEvent> events) {
        List<AnomalyEvent> anomalies = new ArrayList<>();
        long cooldownMs = config.getAnomalyCooldownMinutes() * 60_000L;
        long now = System.currentTimeMillis();

        for (LogPattern pattern : patterns) {
            String patternId = pattern.getId();
            boolean isNew = seenPatterns.add(patternId);

            if (isNew) {
                patternFirstSeen.put(patternId, now);

                Long lastAlert = lastAlertTime.get(patternId);
                if (lastAlert != null && (now - lastAlert) < cooldownMs) {
                    continue;
                }

                AnomalyEvent anomaly = createNewPatternAnomaly(pattern, events);
                if (anomaly != null) {
                    anomalies.add(anomaly);
                    lastAlertTime.put(patternId, now);
                    log.warn("Content anomaly detected - new pattern: {} (severity: {})",
                            pattern.getTemplate(), anomaly.getSeverity());
                }
            }
        }

        return anomalies;
    }

    private AnomalyEvent createNewPatternAnomaly(LogPattern pattern, List<LogEvent> events) {
        LogEvent sampleEvent = findSampleEvent(pattern.getId(), events);
        if (sampleEvent == null) {
            return null;
        }

        AnomalyEvent anomaly = new AnomalyEvent(
                IdUtils.newAnomalyId(),
                AnomalyEvent.AnomalyType.CONTENT,
                Instant.now()
        );

        anomaly.setPatternId(pattern.getId());
        anomaly.setPatternTemplate(pattern.getTemplate());
        anomaly.setServiceName(sampleEvent.getServiceName());
        anomaly.setLevel(sampleEvent.getLevel());
        anomaly.setTraceId(sampleEvent.getTraceId());

        AnomalyEvent.Severity severity = calculateSeverity(pattern, sampleEvent);
        anomaly.setSeverity(severity);

        anomaly.addDetail("firstSeen", Instant.ofEpochMilli(patternFirstSeen.get(pattern.getId())));
        anomaly.addDetail("sampleMessage", sampleEvent.getMessage());
        anomaly.addDetail("sampleHost", sampleEvent.getHostname());
        anomaly.addDetail("isErrorPattern", sampleEvent.getLevel().isMoreSevereThan(LogLevel.WARN));
        anomaly.addDetail("containsCriticalKeyword", containsCriticalKeyword(pattern.getTemplate()));
        anomaly.addDetail("patternStaticTokens", pattern.getStaticTokens().size());
        anomaly.addDetail("patternVariableSlots", pattern.getVariableSlots().size());
        anomaly.addDetail("description", String.format(
                "New log pattern detected: %s (severity: %s, service: %s)",
                pattern.getTemplate(), severity, sampleEvent.getServiceName()
        ));

        return anomaly;
    }

    private LogEvent findSampleEvent(String patternId, List<LogEvent> events) {
        for (LogEvent event : events) {
            if (patternId.equals(event.getPatternId())) {
                return event;
            }
        }
        return null;
    }

    private AnomalyEvent.Severity calculateSeverity(LogPattern pattern, LogEvent event) {
        LogLevel level = event.getLevel();
        String template = pattern.getTemplate().toLowerCase();

        if (level.isMoreSevereThan(LogLevel.ERROR) || containsCriticalKeyword(template)) {
            return AnomalyEvent.Severity.CRITICAL;
        }

        if (level == LogLevel.ERROR) {
            return AnomalyEvent.Severity.HIGH;
        }

        if (level == LogLevel.WARN) {
            return AnomalyEvent.Severity.MEDIUM;
        }

        if (containsSuspiciousPattern(template)) {
            return AnomalyEvent.Severity.MEDIUM;
        }

        return AnomalyEvent.Severity.LOW;
    }

    private boolean containsCriticalKeyword(String template) {
        String lower = template.toLowerCase();
        return criticalKeywords.stream().anyMatch(lower::contains);
    }

    private boolean containsSuspiciousPattern(String template) {
        String lower = template.toLowerCase();
        return lower.contains("failed") || lower.contains("error") ||
               lower.contains("exception") || lower.contains("timeout") ||
               lower.contains("unable") || lower.contains("cannot") ||
               lower.contains("invalid") || lower.contains("rejected");
    }

    public int getNewPatternCountLast(Duration duration) {
        long threshold = System.currentTimeMillis() - duration.toMillis();
        return (int) patternFirstSeen.values().stream()
                .filter(time -> time >= threshold)
                .count();
    }

    public boolean hasSeenPattern(String patternId) {
        return seenPatterns.contains(patternId);
    }

    public Map<String, Object> getDiagnostics() {
        return Map.of(
                "totalSeenPatterns", seenPatterns.size(),
                "newLastHour", getNewPatternCountLast(Duration.ofHours(1)),
                "newLastDay", getNewPatternCountLast(Duration.ofDays(1)),
                "criticalKeywords", criticalKeywords.size()
        );
    }
}
