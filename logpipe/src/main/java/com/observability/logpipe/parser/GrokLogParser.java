package com.observability.logpipe.parser;

import com.observability.logpipe.model.LogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class GrokLogParser implements LogParser {

    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "\\[(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?)\\]");
    private static final Pattern LEVEL_PATTERN = Pattern.compile("\\b(DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\\b");

    @Override
    public String getType() {
        return "grok";
    }

    @Override
    public LogEntry parse(String rawLog, Map<String, Object> config) {
        LogEntry entry = new LogEntry();
        entry.setRawLog(rawLog);
        entry.setTimestamp(LocalDateTime.now());
        entry.setLevel("INFO");
        entry.setMessage(rawLog);

        try {
            Matcher timestampMatcher = TIMESTAMP_PATTERN.matcher(rawLog);
            if (timestampMatcher.find()) {
                try {
                    String ts = timestampMatcher.group(1).replace(' ', 'T');
                    entry.setTimestamp(LocalDateTime.parse(ts,
                            DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                } catch (Exception e) {
                    log.debug("Failed to parse timestamp: {}", e.getMessage());
                }
            }

            Matcher levelMatcher = LEVEL_PATTERN.matcher(rawLog);
            if (levelMatcher.find()) {
                entry.setLevel(levelMatcher.group(1));
            }

            String customPattern = (String) config.get("pattern");
            if (customPattern != null) {
                Map<String, String> fields = new HashMap<>();
                entry.setFields(new HashMap<>());
                entry.setTags(new HashMap<>());
            }
        } catch (Exception e) {
            log.warn("Failed to parse grok log: {}", e.getMessage());
        }

        return entry;
    }
}
