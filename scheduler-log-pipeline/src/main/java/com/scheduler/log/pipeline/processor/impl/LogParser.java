package com.scheduler.log.pipeline.processor.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.log.pipeline.model.LogEntry;
import com.scheduler.log.pipeline.processor.LogProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogParser implements LogProcessor {

    private final ObjectMapper objectMapper;

    private static final Pattern LOG_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+" +
            "\\[(.*?)\\]\\s+" +
            "(DEBUG|INFO|WARN|ERROR)\\s+" +
            "(.*?)\\s+-\\s+(.*)$"
    );

    @Override
    public String getName() {
        return "parser";
    }

    @Override
    public Mono<LogEntry> process(LogEntry entry) {
        return Mono.fromCallable(() -> {
            if (entry.getStructuredData() != null && !entry.getStructuredData().isEmpty()) {
                return entry;
            }

            Map<String, Object> structured = parseLogLine(entry.getMessage());
            entry.setStructuredData(structured);

            if (structured.containsKey("timestamp")) {
                entry.setTimestamp((LocalDateTime) structured.get("timestamp"));
            }
            if (structured.containsKey("level")) {
                entry.setLevel((String) structured.get("level"));
            }
            if (structured.containsKey("logger")) {
                entry.setLoggerName((String) structured.get("logger"));
            }
            if (structured.containsKey("thread")) {
                entry.setThreadName((String) structured.get("thread"));
            }

            return entry;
        });
    }

    private Map<String, Object> parseLogLine(String message) {
        Map<String, Object> result = new HashMap<>();

        if (message.startsWith("{") && message.endsWith("}")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> jsonMap = objectMapper.readValue(message, Map.class);
                result.putAll(jsonMap);
                return result;
            } catch (Exception e) {
                log.debug("Failed to parse JSON log: {}", e.getMessage());
            }
        }

        Matcher matcher = LOG_PATTERN.matcher(message);
        if (matcher.matches()) {
            result.put("timestamp", LocalDateTime.parse(matcher.group(1)));
            result.put("thread", matcher.group(2));
            result.put("level", matcher.group(3));
            result.put("logger", matcher.group(4));
            result.put("message", matcher.group(5));
        } else {
            result.put("message", message);
            result.put("level", "INFO");
            result.put("timestamp", LocalDateTime.now());
        }

        return result;
    }
}
