package com.loganalytics.detector.anomaly;

import com.loganalytics.common.model.AnomalyEvent;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.common.model.LogPattern;
import com.loganalytics.common.util.IdUtils;
import com.loganalytics.common.util.TimeUtils;
import com.loganalytics.detector.config.DetectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class CorrelationAnomalyDetector {
    private static final Logger log = LoggerFactory.getLogger(CorrelationAnomalyDetector.class);

    private final DetectorConfig config;
    private final Map<String, Deque<LogEvent>> errorWindows;
    private final Map<String, Set<String>> expectedCounterparts;
    private final Map<String, Long> lastAlertTime;

    public CorrelationAnomalyDetector(DetectorConfig config) {
        this.config = config;
        this.errorWindows = new ConcurrentHashMap<>();
        this.expectedCounterparts = new ConcurrentHashMap<>();
        this.lastAlertTime = new ConcurrentHashMap<>();
        initializeExpectedCounterparts();
    }

    private void initializeExpectedCounterparts() {
        expectedCounterparts.put("database connection error", Set.of(
                "database connection retry",
                "database connection restored",
                "failover to standby database"
        ));
        expectedCounterparts.put("connection refused", Set.of(
                "connection retry",
                "circuit breaker opened",
                "fallback executed"
        ));
        expectedCounterparts.put("timeout exception", Set.of(
                "retry attempt",
                "circuit breaker opened",
                "timeout fallback"
        ));
        expectedCounterparts.put("out of memory error", Set.of(
                "gc started",
                "heap dump generated",
                "memory pressure high"
        ));
        expectedCounterparts.put("null pointer exception", Set.of(
                "validation failed",
                "input validation error",
                "data integrity check"
        ));
    }

    public List<AnomalyEvent> detect(List<LogEvent> events) {
        List<AnomalyEvent> anomalies = new ArrayList<>();
        long cooldownMs = config.getAnomalyCooldownMinutes() * 60_000L;
        long now = System.currentTimeMillis();

        for (LogEvent event : events) {
            if (event.getLevel() == LogLevel.ERROR || event.getLevel() == LogLevel.FATAL) {
                trackError(event);
            } else if (event.getLevel() == LogLevel.WARN) {
                markCounterpart(event);
            }
        }

        anomalies.addAll(checkMissingCounterparts(now, cooldownMs));
        cleanupExpiredWindows(now);

        return anomalies;
    }

    private void trackError(LogEvent event) {
        String serviceKey = getServiceKey(event);
        Deque<LogEvent> window = errorWindows.computeIfAbsent(
                serviceKey, k -> new ConcurrentLinkedDeque<>()
        );

        window.offerLast(event);

        log.debug("Tracked ERROR for correlation: service={}, pattern={}",
                event.getServiceName(), event.getPatternId());
    }

    private void markCounterpart(LogEvent event) {
        String message = event.getMessage() != null ? event.getMessage().toLowerCase() : "";

        for (Map.Entry<String, Set<String>> entry : expectedCounterparts.entrySet()) {
            String errorPattern = entry.getKey();
            Set<String> counterparts = entry.getValue();

            for (String counterpart : counterparts) {
                if (message.contains(counterpart.toLowerCase())) {
                    String serviceKey = getServiceKey(event);
                    Deque<LogEvent> window = errorWindows.get(serviceKey);

                    if (window != null) {
                        for (LogEvent errorEvent : window) {
                            String errorMsg = errorEvent.getMessage() != null ?
                                    errorEvent.getMessage().toLowerCase() : "";
                            if (errorMsg.contains(errorPattern)) {
                                errorEvent.addTag("counterpart_found:" + counterpart);
                                log.debug("Found counterpart for ERROR: {} -> {}",
                                        errorPattern, counterpart);
                            }
                        }
                    }
                }
            }
        }
    }

    private List<AnomalyEvent> checkMissingCounterparts(long now, long cooldownMs) {
        List<AnomalyEvent> anomalies = new ArrayList<>();
        long windowMs = config.getCorrelationWindowSeconds() * 1000L;

        for (Map.Entry<String, Deque<LogEvent>> entry : errorWindows.entrySet()) {
            String serviceKey = entry.getKey();
            Deque<LogEvent> window = entry.getValue();

            Iterator<LogEvent> it = window.iterator();
            while (it.hasNext()) {
                LogEvent errorEvent = it.next();
                long age = now - errorEvent.getTimestamp().toEpochMilli();

                if (age >= windowMs) {
                    boolean hasCounterpart = errorEvent.getTags().stream()
                            .anyMatch(tag -> tag.startsWith("counterpart_found:"));

                    if (!hasCounterpart) {
                        String alertKey = serviceKey + ":" + errorEvent.getPatternId();
                        Long lastAlert = lastAlertTime.get(alertKey);

                        if (lastAlert == null || (now - lastAlert) >= cooldownMs) {
                            AnomalyEvent anomaly = createCorrelationAnomaly(errorEvent);
                            anomalies.add(anomaly);
                            lastAlertTime.put(alertKey, now);

                            log.warn("Correlation anomaly detected: missing counterpart for ERROR " +
                                            "service={}, pattern={}, age={}s",
                                    errorEvent.getServiceName(), errorEvent.getPatternId(), age / 1000);
                        }
                    }

                    it.remove();
                } else {
                    break;
                }
            }
        }

        return anomalies;
    }

    private AnomalyEvent createCorrelationAnomaly(LogEvent errorEvent) {
        AnomalyEvent anomaly = new AnomalyEvent(
                IdUtils.newAnomalyId(),
                AnomalyEvent.AnomalyType.CORRELATION,
                Instant.now()
        );

        anomaly.setPatternId(errorEvent.getPatternId());
        anomaly.setPatternTemplate(errorEvent.getPatternTemplate());
        anomaly.setServiceName(errorEvent.getServiceName());
        anomaly.setLevel(errorEvent.getLevel());
        anomaly.setTraceId(errorEvent.getTraceId());
        anomaly.setSeverity(determineSeverity(errorEvent));

        anomaly.addDetail("errorMessage", errorEvent.getMessage());
        anomaly.addDetail("errorTimestamp", errorEvent.getTimestamp());
        anomaly.addDetail("windowSeconds", config.getCorrelationWindowSeconds());
        anomaly.addDetail("expectedCounterparts", findExpectedCounterparts(errorEvent));
        anomaly.addDetail("traceId", errorEvent.getTraceId());
        anomaly.addDetail("hostname", errorEvent.getHostname());
        anomaly.addDetail("description", String.format(
                "ERROR occurred without expected WARNING counterpart within %ds window: %s (service: %s)",
                config.getCorrelationWindowSeconds(),
                errorEvent.getPatternTemplate(),
                errorEvent.getServiceName()
        ));

        return anomaly;
    }

    private AnomalyEvent.Severity determineSeverity(LogEvent event) {
        if (event.getLevel() == LogLevel.FATAL) {
            return AnomalyEvent.Severity.CRITICAL;
        }

        String message = event.getMessage() != null ? event.getMessage().toLowerCase() : "";
        if (message.contains("database") || message.contains("connection") ||
            message.contains("timeout") || message.contains("out of memory")) {
            return AnomalyEvent.Severity.HIGH;
        }

        return AnomalyEvent.Severity.MEDIUM;
    }

    private List<String> findExpectedCounterparts(LogEvent errorEvent) {
        List<String> expected = new ArrayList<>();
        String message = errorEvent.getMessage() != null ? errorEvent.getMessage().toLowerCase() : "";

        for (Map.Entry<String, Set<String>> entry : expectedCounterparts.entrySet()) {
            if (message.contains(entry.getKey())) {
                expected.addAll(entry.getValue());
            }
        }

        if (expected.isEmpty()) {
            expected.add("retry attempt");
            expected.add("fallback execution");
            expected.add("recovery action");
        }

        return expected;
    }

    private String getServiceKey(LogEvent event) {
        String service = event.getServiceName() != null ? event.getServiceName() : "unknown";
        String host = event.getHostname() != null ? event.getHostname() : "unknown";
        return service + ":" + host;
    }

    private void cleanupExpiredWindows(long now) {
        long maxAge = config.getCorrelationWindowSeconds() * 2000L;

        errorWindows.entrySet().removeIf(entry -> {
            Deque<LogEvent> window = entry.getValue();
            if (window.isEmpty()) return true;

            LogEvent oldest = window.peekFirst();
            if (oldest == null) return true;

            return (now - oldest.getTimestamp().toEpochMilli()) > maxAge;
        });

        lastAlertTime.entrySet().removeIf(entry ->
                (now - entry.getValue()) > (config.getAnomalyCooldownMinutes() * 60_000L * 10)
        );
    }

    public void addExpectedCounterpart(String errorPattern, String counterpart) {
        expectedCounterparts.computeIfAbsent(errorPattern, k -> new HashSet<>())
                .add(counterpart);
    }

    public Map<String, Object> getDiagnostics() {
        int totalTracked = errorWindows.values().stream().mapToInt(Deque::size).sum();
        return Map.of(
                "trackedErrors", totalTracked,
                "activeServices", errorWindows.size(),
                "expectedRules", expectedCounterparts.size(),
                "windowSeconds", config.getCorrelationWindowSeconds()
        );
    }
}
