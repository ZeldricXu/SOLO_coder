package com.loganalytics.api.service;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.common.util.IdUtils;
import com.loganalytics.common.util.JsonUtils;
import com.loganalytics.query.QueryParser;
import com.loganalytics.query.ast.ASTNode;
import com.loganalytics.query.ast.SqlTranslator;
import com.loganalytics.storage.query.QueryCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class LogSearchService {
    private static final Logger log = LoggerFactory.getLogger(LogSearchService.class);

    private final Map<String, LogEvent> logIndex = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private QueryCoordinator queryCoordinator;

    public Map<String, Object> searchLogs(String query, String serviceName, String level,
                                          String patternId, Instant startTime, Instant endTime,
                                          int page, int pageSize) {
        List<LogEvent> results = new ArrayList<>();

        String[] services = {"payment", "user", "order", "gateway"};
        LogLevel[] levels = {LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.DEBUG};
        String[] messages = {
                "Request processed successfully in 45ms",
                "User login successful: user_id=12345",
                "Connection timeout after 30s to db-primary:5432",
                "Database query failed: SQLSyntaxErrorException",
                "Invalid request: missing required field 'user_id'",
                "Rate limit exceeded for client 192.168.1.100",
                "Cache miss for key: user_profile_12345",
                "Starting scheduled task: cleanup_expired_sessions"
        };

        Random random = new Random(query != null ? query.hashCode() : 42);
        int totalResults = 50 + random.nextInt(100);

        for (int i = 0; i < Math.min(pageSize, totalResults - page * pageSize); i++) {
            int idx = page * pageSize + i;
            if (idx >= totalResults) break;

            LogEvent event = generateMockLog(random, services, levels, messages, startTime, endTime);
            if (matchFilters(event, serviceName, level, patternId, query)) {
                results.add(event);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("query", query);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("total", totalResults);
        result.put("matches", results.size());
        result.put("startTime", startTime.toString());
        result.put("endTime", endTime.toString());
        result.put("logs", results.stream().map(this::toMap).collect(Collectors.toList()));

        return result;
    }

    public Map<String, Object> searchByNaturalLanguage(String naturalQuery, int page, int pageSize) {
        try {
            QueryParser.ParseResult parseResult = QueryParser.parse(naturalQuery);
            ASTNode ast = parseResult.getAst();
            String sql = SqlTranslator.translate(ast);

            log.info("Parsed query '{}' to SQL: {}", naturalQuery, sql);

            Instant startTime = parseResult.getStartTime() != null
                    ? parseResult.getStartTime()
                    : Instant.now().minusSeconds(3600);
            Instant endTime = parseResult.getEndTime() != null
                    ? parseResult.getEndTime()
                    : Instant.now();

            return searchLogs(
                    parseResult.getKeywords(),
                    parseResult.getFieldValue("service"),
                    parseResult.getFieldValue("level"),
                    parseResult.getFieldValue("pattern"),
                    startTime,
                    endTime,
                    page,
                    pageSize
            );
        } catch (Exception e) {
            log.error("Error parsing natural language query: {}", naturalQuery, e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to parse query: " + e.getMessage());
            error.put("query", naturalQuery);
            return error;
        }
    }

    private LogEvent generateMockLog(Random random, String[] services, LogLevel[] levels,
                                     String[] messages, Instant startTime, Instant endTime) {
        LogEvent event = new LogEvent();
        event.setId(IdUtils.generateId("log"));
        event.setServiceName(services[random.nextInt(services.length)]);
        event.setLevel(levels[random.nextInt(levels.length)]);
        event.setMessage(messages[random.nextInt(messages.length)]);
        event.setHostname("host-" + (100 + random.nextInt(50)));
        event.setTimestamp(generateRandomTime(startTime, endTime, random));
        event.setTraceId("trace-" + random.nextInt(100000));
        event.setSpanId("span-" + random.nextInt(100000));
        event.setPatternId("pattern-" + (100 + random.nextInt(50)));

        Map<String, Object> fields = new HashMap<>();
        fields.put("duration", random.nextInt(500) + "ms");
        fields.put("statusCode", 200 + random.nextInt(300));
        fields.put("userId", "user-" + random.nextInt(10000));
        fields.put("requestId", "req-" + random.nextInt(100000));
        event.setFields(fields);

        return event;
    }

    private Instant generateRandomTime(Instant start, Instant end, Random random) {
        long startMillis = start.toEpochMilli();
        long endMillis = end.toEpochMilli();
        long randomMillis = startMillis + (long) (random.nextDouble() * (endMillis - startMillis));
        return Instant.ofEpochMilli(randomMillis);
    }

    private boolean matchFilters(LogEvent event, String serviceName, String level,
                                 String patternId, String query) {
        if (serviceName != null && !serviceName.equals("*") && !serviceName.equals(event.getServiceName())) {
            return false;
        }
        if (level != null && !level.equalsIgnoreCase(event.getLevel().name())) {
            return false;
        }
        if (patternId != null && !patternId.equals(event.getPatternId())) {
            return false;
        }
        if (query != null && !query.isEmpty() &&
                !event.getMessage().toLowerCase().contains(query.toLowerCase())) {
            return false;
        }
        return true;
    }

    private Map<String, Object> toMap(LogEvent event) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", event.getId());
        map.put("timestamp", event.getTimestamp() != null ? event.getTimestamp().toString() : null);
        map.put("serviceName", event.getServiceName());
        map.put("level", event.getLevel() != null ? event.getLevel().name() : null);
        map.put("message", event.getMessage());
        map.put("hostname", event.getHostname());
        map.put("traceId", event.getTraceId());
        map.put("spanId", event.getSpanId());
        map.put("patternId", event.getPatternId());
        map.put("fields", event.getFields());
        return map;
    }

    public void indexLog(LogEvent event) {
        if (event.getId() == null) {
            event.setId(IdUtils.generateId("log"));
        }
        logIndex.put(event.getId(), event);
    }

    public Optional<LogEvent> getLogById(String id) {
        return Optional.ofNullable(logIndex.get(id));
    }

    public Map<String, Object> getLogContext(String logId, int before, int after) {
        Optional<LogEvent> logOpt = getLogById(logId);
        if (logOpt.isEmpty()) {
            return Map.of("error", "Log not found");
        }

        LogEvent center = logOpt.get();
        List<Map<String, Object>> contextLogs = new ArrayList<>();

        Random random = new Random(logId.hashCode());
        String[] services = {center.getServiceName()};
        LogLevel[] levels = {LogLevel.INFO, LogLevel.DEBUG, LogLevel.WARN};
        String[] messages = {
                "Processing request...",
                "Validating input parameters",
                "Connecting to database",
                "Executing query",
                "Request processed"
        };

        for (int i = before; i > 0; i--) {
            LogEvent e = generateMockLog(random, services, levels, messages,
                    center.getTimestamp().minusSeconds(i * 5),
                    center.getTimestamp().minusSeconds((i - 1) * 5));
            contextLogs.add(toMap(e));
        }

        contextLogs.add(toMap(center));

        for (int i = 1; i <= after; i++) {
            LogEvent e = generateMockLog(random, services, levels, messages,
                    center.getTimestamp().plusSeconds((i - 1) * 5),
                    center.getTimestamp().plusSeconds(i * 5));
            contextLogs.add(toMap(e));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("centerLogId", logId);
        result.put("beforeCount", before);
        result.put("afterCount", after);
        result.put("totalContext", contextLogs.size());
        result.put("logs", contextLogs);

        return result;
    }
}
