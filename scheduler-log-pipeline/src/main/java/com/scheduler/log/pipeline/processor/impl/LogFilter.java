package com.scheduler.log.pipeline.processor.impl;

import com.scheduler.log.pipeline.model.LogEntry;
import com.scheduler.log.pipeline.processor.LogProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class LogFilter implements LogProcessor {

    @Value("${log.pipeline.filter.min-level:INFO}")
    private String minLevel;

    @Value("${log.pipeline.filter.excluded-loggers:}")
    private List<String> excludedLoggers;

    private static final Map<String, Integer> LEVEL_ORDER = Map.of(
            "TRACE", 0,
            "DEBUG", 1,
            "INFO", 2,
            "WARN", 3,
            "ERROR", 4
    );

    @Override
    public String getName() {
        return "filter";
    }

    @Override
    public Mono<LogEntry> process(LogEntry entry) {
        return Mono.fromCallable(() -> {
            if (!isLevelAllowed(entry.getLevel())) {
                entry.setFiltered(true);
                entry.setFilterReason("Level below threshold: " + minLevel);
                return entry;
            }

            if (isLoggerExcluded(entry.getLoggerName())) {
                entry.setFiltered(true);
                entry.setFilterReason("Logger in exclusion list");
                return entry;
            }

            if (containsSensitiveData(entry.getMessage())) {
                entry.setMessage(maskSensitiveData(entry.getMessage()));
            }

            return entry;
        });
    }

    private boolean isLevelAllowed(String level) {
        Integer entryLevel = LEVEL_ORDER.getOrDefault(level.toUpperCase(), 2);
        Integer minLevelOrder = LEVEL_ORDER.getOrDefault(minLevel.toUpperCase(), 2);
        return entryLevel >= minLevelOrder;
    }

    private boolean isLoggerExcluded(String loggerName) {
        if (loggerName == null || excludedLoggers == null || excludedLoggers.isEmpty()) {
            return false;
        }
        return excludedLoggers.stream()
                .anyMatch(excluded -> loggerName.startsWith(excluded));
    }

    private boolean containsSensitiveData(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("password") ||
               lower.contains("secret") ||
               lower.contains("token") ||
               lower.contains("apikey") ||
               lower.contains("private key");
    }

    private String maskSensitiveData(String message) {
        message = message.replaceAll("(?i)(password[=:][\"']?)[^\"'&\\s]+", "$1***");
        message = message.replaceAll("(?i)(token[=:][\"']?)[^\"'&\\s]+", "$1***");
        message = message.replaceAll("(?i)(secret[=:][\"']?)[^\"'&\\s]+", "$1***");
        return message;
    }
}
