package com.loganalytics.test;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.common.util.IdUtils;

import java.time.Instant;
import java.util.*;

public class LogEventGenerator {
    private static final Random random = new Random();

    private static final String[] SERVICES = {
            "payment-service", "user-service", "order-service",
            "gateway-service", "notification-service", "inventory-service"
    };

    private static final String[] HOSTNAMES = {
            "host-101", "host-102", "host-103", "host-104", "host-105"
    };

    private static final Map<LogLevel, String[]> MESSAGES = Map.of(
            LogLevel.INFO, new String[]{
                    "Request processed successfully in %dms",
                    "User %s logged in successfully",
                    "Scheduled task completed: %s",
                    "Cache hit ratio: %.2f%%",
                    "Connection pool stats: active=%d, idle=%d"
            },
            LogLevel.WARN, new String[]{
                    "Slow query detected: %dms for query %s",
                    "Connection pool nearing capacity: %d/%d",
                    "Retry attempt %d for service %s",
                    "Deprecated API endpoint called: %s",
                    "Memory usage exceeds threshold: %.2f%%"
            },
            LogLevel.ERROR, new String[]{
                    "Connection timeout after %dms to %s",
                    "Database query failed: %s",
                    "Null pointer exception at %s",
                    "Invalid request: %s",
                    "Service %s unavailable, circuit breaker opened"
            },
            LogLevel.DEBUG, new String[]{
                    "Entering method %s with params: %s",
                    "Cache miss for key: %s",
                    "SQL query: %s",
                    "Response payload: %s",
                    "Processing step %d/%d"
            }
    );

    public static LogEvent generateRandomEvent() {
        LogEvent event = new LogEvent();
        event.setId(IdUtils.generateId("log"));

        LogLevel level = LogLevel.values()[random.nextInt(LogLevel.values().length - 1)];
        String service = SERVICES[random.nextInt(SERVICES.length)];
        String hostname = HOSTNAMES[random.nextInt(HOSTNAMES.length)];

        event.setLevel(level);
        event.setServiceName(service);
        event.setHostname(hostname);
        event.setMessage(generateMessage(level));
        event.setTimestamp(Instant.now().minusMillis(random.nextInt(3600000)));
        event.setTraceId("trace-" + random.nextInt(100000));
        event.setSpanId("span-" + random.nextInt(100000));
        event.setPatternId("pattern-" + (100 + random.nextInt(50)));

        Map<String, Object> fields = new HashMap<>();
        fields.put("duration", random.nextInt(1000) + "ms");
        fields.put("statusCode", 200 + random.nextInt(300));
        fields.put("userId", "user-" + random.nextInt(10000));
        fields.put("requestId", "req-" + random.nextInt(100000));
        event.setFields(fields);

        return event;
    }

    private static String generateMessage(LogLevel level) {
        String[] templates = MESSAGES.getOrDefault(level, MESSAGES.get(LogLevel.INFO));
        String template = templates[random.nextInt(templates.length)];

        List<Object> args = new ArrayList<>();
        for (char c : template.toCharArray()) {
            if (c == '%') {
                if (template.contains("%d")) args.add(random.nextInt(1000));
                if (template.contains("%s")) args.add("param-" + random.nextInt(100));
                if (template.contains("%.2f")) args.add(random.nextDouble() * 100);
            }
        }

        return String.format(template, args.toArray());
    }

    public static List<LogEvent> generateEvents(int count) {
        List<LogEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(generateRandomEvent());
        }
        return events;
    }

    public static List<LogEvent> generateEventsForService(String serviceName, int count, LogLevel level) {
        List<LogEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LogEvent event = generateRandomEvent();
            event.setServiceName(serviceName);
            event.setLevel(level);
            events.add(event);
        }
        return events;
    }

    public static LogEvent generateErrorEvent(String serviceName, String errorPattern) {
        LogEvent event = generateRandomEvent();
        event.setServiceName(serviceName);
        event.setLevel(LogLevel.ERROR);
        event.setMessage(errorPattern);
        return event;
    }
}
