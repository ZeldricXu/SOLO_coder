package com.enterprise.gateway.logprocessor.benchmark;

import com.enterprise.gateway.logprocessor.model.LogEntry;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

public final class BenchmarkDataGenerator {

    private static final String[] SERVICES = {
            "user-service", "order-service", "payment-service",
            "inventory-service", "notification-service", "api-gateway",
            "auth-service", "search-service", "cache-service", "queue-service"
    };

    private static final String[] LEVELS = {"DEBUG", "INFO", "WARN", "ERROR", "TRACE"};

    private static final String[] STATUS_CODES = {"200", "201", "204", "301", "302", "400", "401", "403", "404", "500"};

    private static final String[] METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH"};

    private static final String[] PATHS = {
            "/api/v1/users", "/api/v1/orders", "/api/v1/payments",
            "/api/v1/products", "/api/v1/search", "/health",
            "/metrics", "/api/v1/auth/login", "/api/v1/auth/register"
    };

    private static final String[] MESSAGES = {
            "User logged in successfully", "Order processed", "Payment completed",
            "Cache invalidated", "Request timed out", "Database connection established",
            "Configuration reloaded", "Thread pool exhausted", "Rate limit exceeded",
            "Circuit breaker opened", "Retry succeeded", "Data synchronized"
    };

    private static final String[] SYSLOG_HOSTS = {"server01", "server02", "gateway01", "db01", "cache01"};

    private static final DateTimeFormatter NGINX_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    private static final DateTimeFormatter LOGBACK_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private BenchmarkDataGenerator() {
    }

    public static List<String> generateMixedLogLines(int count) {
        List<String> lines = new ArrayList<>(count);
        Random random = new Random(42);

        int perFormat = count / 5;

        for (int i = 0; i < perFormat; i++) {
            lines.add(generateJsonLogLine(random));
        }
        for (int i = 0; i < perFormat; i++) {
            lines.add(generateNginxLogLine(random));
        }
        for (int i = 0; i < perFormat; i++) {
            lines.add(generateLogbackLogLine(random));
        }
        for (int i = 0; i < perFormat; i++) {
            lines.add(generateSyslogLine(random));
        }
        for (int i = 0; i < perFormat; i++) {
            lines.add(generateCsvLogLine(random));
        }

        return lines;
    }

    public static List<LogEntry> generateLogEntries(int count, long startTime, long endTime) {
        List<LogEntry> entries = new ArrayList<>(count);
        Random random = new Random(12345);

        for (int i = 0; i < count; i++) {
            long timestamp = startTime + (long) (random.nextDouble() * (endTime - startTime));
            entries.add(generateLogEntry(random, timestamp));
        }

        return entries;
    }

    public static List<String> generateInternTestStrings(int count) {
        List<String> strings = new ArrayList<>(count);
        Random random = new Random(67890);

        int uniqueCount = (int) (count * 0.2);
        int duplicateCount = count - uniqueCount;

        List<String> baseStrings = generateBaseStrings(uniqueCount, random);

        for (int i = 0; i < duplicateCount; i++) {
            strings.add(baseStrings.get(random.nextInt(baseStrings.size())));
        }

        for (int i = 0; i < uniqueCount; i++) {
            strings.add(baseStrings.get(i));
        }

        return strings;
    }

    private static List<String> generateBaseStrings(int count, Random random) {
        List<String> base = new ArrayList<>();

        for (String service : SERVICES) {
            base.add(service);
        }
        for (String level : LEVELS) {
            base.add(level);
        }
        for (String status : STATUS_CODES) {
            base.add(status);
        }

        while (base.size() < count) {
            base.add(UUID.randomUUID().toString().substring(0, 20));
        }

        return base.subList(0, count);
    }

    private static String generateJsonLogLine(Random random) {
        long timestamp = System.currentTimeMillis() - random.nextInt(86400000);
        String service = SERVICES[random.nextInt(SERVICES.length)];
        String level = LEVELS[random.nextInt(LEVELS.length)];
        String message = MESSAGES[random.nextInt(MESSAGES.length)];
        String traceId = UUID.randomUUID().toString();
        String statusCode = STATUS_CODES[random.nextInt(STATUS_CODES.length)];
        String method = METHODS[random.nextInt(METHODS.length)];
        String path = PATHS[random.nextInt(PATHS.length)];
        int duration = random.nextInt(500);

        return String.format(
                "{\"timestamp\":%d,\"service\":\"%s\",\"level\":\"%s\",\"message\":\"%s\",\"traceId\":\"%s\",\"statusCode\":\"%s\",\"method\":\"%s\",\"path\":\"%s\",\"duration\":\"%dms\"}",
                timestamp, service, level, message, traceId, statusCode, method, path, duration
        );
    }

    private static String generateNginxLogLine(Random random) {
        String ip = String.format("%d.%d.%d.%d",
                random.nextInt(255) + 1, random.nextInt(256), random.nextInt(256), random.nextInt(256));
        LocalDateTime now = LocalDateTime.now().minusDays(random.nextInt(7));
        String timestamp = now.format(NGINX_DATE_FORMAT);
        String method = METHODS[random.nextInt(METHODS.length)];
        String path = PATHS[random.nextInt(PATHS.length)];
        int status = Integer.parseInt(STATUS_CODES[random.nextInt(STATUS_CODES.length)]);
        int bodySize = random.nextInt(10000) + 100;

        return String.format("%s - - [%s] \"%s %s HTTP/1.1\" %d %d \"-\" \"curl/7.68.0\"",
                ip, timestamp, method, path, status, bodySize);
    }

    private static String generateLogbackLogLine(Random random) {
        LocalDateTime now = LocalDateTime.now().minusDays(random.nextInt(7));
        String timestamp = now.format(LOGBACK_DATE_FORMAT);
        String thread = "http-nio-8080-exec-" + random.nextInt(200);
        String level = LEVELS[random.nextInt(LEVELS.length)];
        String service = SERVICES[random.nextInt(SERVICES.length)];
        String message = MESSAGES[random.nextInt(MESSAGES.length)];

        return String.format("%s [%s] %s %s - %s", timestamp, thread, level, service, message);
    }

    private static String generateSyslogLine(Random random) {
        int priority = random.nextInt(192);
        String month = MONTHS[random.nextInt(MONTHS.length)];
        int day = random.nextInt(28) + 1;
        int hour = random.nextInt(24);
        int minute = random.nextInt(60);
        int second = random.nextInt(60);
        String host = SYSLOG_HOSTS[random.nextInt(SYSLOG_HOSTS.length)];
        String message = MESSAGES[random.nextInt(MESSAGES.length)];

        return String.format("<%d>%s %2d %02d:%02d:%02d %s %s",
                priority, month, day, hour, minute, second, host, message);
    }

    private static String generateCsvLogLine(Random random) {
        long timestamp = System.currentTimeMillis() - random.nextInt(86400000);
        String service = SERVICES[random.nextInt(SERVICES.length)];
        String level = LEVELS[random.nextInt(LEVELS.length)];
        String message = MESSAGES[random.nextInt(MESSAGES.length)];
        String traceId = UUID.randomUUID().toString();
        String statusCode = STATUS_CODES[random.nextInt(STATUS_CODES.length)];
        String method = METHODS[random.nextInt(METHODS.length)];
        String path = PATHS[random.nextInt(PATHS.length)];
        int duration = random.nextInt(500);

        return String.format("%d,%s,%s,%s,%s,%s,%s,%s,%dms",
                timestamp, service, level, message, traceId, statusCode, method, path, duration);
    }

    private static LogEntry generateLogEntry(Random random, long timestamp) {
        return LogEntry.builder()
                .timestamp(timestamp)
                .service(SERVICES[random.nextInt(SERVICES.length)])
                .level(LEVELS[random.nextInt(LEVELS.length)])
                .message(MESSAGES[random.nextInt(MESSAGES.length)])
                .traceId(UUID.randomUUID().toString())
                .statusCode(STATUS_CODES[random.nextInt(STATUS_CODES.length)])
                .method(METHODS[random.nextInt(METHODS.length)])
                .path(PATHS[random.nextInt(PATHS.length)])
                .duration(random.nextInt(500) + "ms")
                .build();
    }
}
