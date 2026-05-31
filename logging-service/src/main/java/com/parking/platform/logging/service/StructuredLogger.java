package com.parking.platform.logging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parking.platform.logging.entity.LogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StructuredLogger {

    private static final Logger log = LoggerFactory.getLogger(StructuredLogger.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, List<LogEntry>> logStore = new ConcurrentHashMap<>();

    public void log(String level, String service, String message, String traceId, String requestId, String userId, Map<String, Object> context) {
        LogEntry entry = new LogEntry();
        entry.setLevel(level);
        entry.setService(service);
        entry.setMessage(message);
        entry.setTraceId(traceId);
        entry.setRequestId(requestId);
        entry.setUserId(userId);
        entry.setContext(context);

        logStore.computeIfAbsent(service, k -> new ArrayList<>()).add(entry);
        if (logStore.get(service).size() > 10000) {
            logStore.get(service).remove(0);
        }

        outputJson(entry);
    }

    private void outputJson(LogEntry entry) {
        try {
            String json = objectMapper.writeValueAsString(entry.toMap());
            switch (entry.getLevel().toUpperCase()) {
                case "ERROR": log.error(json); break;
                case "WARN": log.warn(json); break;
                case "DEBUG": log.debug(json); break;
                case "TRACE": log.trace(json); break;
                default: log.info(json); break;
            }
        } catch (Exception e) {
            log.error("Failed to serialize log entry", e);
        }
    }

    public List<LogEntry> queryLogs(String service, String level, Instant from, Instant to, Integer limit) {
        List<LogEntry> result = new ArrayList<>();
        List<LogEntry> logs = service != null ? logStore.get(service) : new ArrayList<>();
        if (logs == null) return result;

        for (LogEntry entry : logs) {
            if (level != null && !level.equalsIgnoreCase(entry.getLevel())) continue;
            if (from != null && entry.getTimestamp().isBefore(from)) continue;
            if (to != null && entry.getTimestamp().isAfter(to)) continue;
            result.add(entry);
        }

        result.sort((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()));
        int actualLimit = limit != null ? Math.min(limit, result.size()) : result.size();
        return result.subList(0, actualLimit);
    }

    public Map<String, Long> getStatistics() {
        return Map.of(
                "totalServices", (long) logStore.size(),
                "totalLogs", logStore.values().stream().mapToLong(List::size).sum()
        );
    }

    public void info(String service, String message, Map<String, Object> context) {
        log("INFO", service, message, null, null, null, context);
    }

    public void warn(String service, String message, Map<String, Object> context) {
        log("WARN", service, message, null, null, null, context);
    }

    public void error(String service, String message, String exception, Map<String, Object> context) {
        log("ERROR", service, message, null, null, null, context);
    }
}
