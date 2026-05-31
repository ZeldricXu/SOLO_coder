package com.observability.logpipe.filter;

import com.observability.logpipe.model.LogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class LevelFilter implements LogFilter {

    private static final Set<String> LEVELS = Set.of("DEBUG", "INFO", "WARN", "WARNING", "ERROR", "FATAL");

    @Override
    public String getType() {
        return "level";
    }

    @Override
    public boolean accept(LogEntry entry, Map<String, Object> config) {
        String minLevel = (String) config.getOrDefault("minLevel", "DEBUG");
        String entryLevel = entry.getLevel() != null ? entry.getLevel().toUpperCase() : "INFO";

        int minIndex = getLevelIndex(minLevel);
        int entryIndex = getLevelIndex(entryLevel);

        return entryIndex >= minIndex;
    }

    private int getLevelIndex(String level) {
        return switch (level.toUpperCase()) {
            case "DEBUG" -> 0;
            case "INFO" -> 1;
            case "WARN", "WARNING" -> 2;
            case "ERROR" -> 3;
            case "FATAL" -> 4;
            default -> 1;
        };
    }
}
