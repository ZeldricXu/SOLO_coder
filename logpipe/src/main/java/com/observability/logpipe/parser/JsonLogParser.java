package com.observability.logpipe.parser;

import com.alibaba.fastjson2.JSON;
import com.observability.logpipe.model.LogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class JsonLogParser implements LogParser {

    @Override
    public String getType() {
        return "json";
    }

    @Override
    @SuppressWarnings("unchecked")
    public LogEntry parse(String rawLog, Map<String, Object> config) {
        try {
            Map<String, Object> parsed = JSON.parseObject(rawLog, Map.class);
            LogEntry entry = new LogEntry();
            entry.setRawLog(rawLog);
            entry.setTimestamp(parsed.get("@timestamp") != null ?
                    LocalDateTime.parse(parsed.get("@timestamp").toString()) :
                    LocalDateTime.now());
            entry.setLevel((String) parsed.getOrDefault("level", "INFO"));
            entry.setMessage((String) parsed.getOrDefault("message", ""));
            entry.setService((String) parsed.get("service"));
            entry.setHost((String) parsed.get("host"));
            entry.setTraceId((String) parsed.get("traceId"));
            entry.setTags((Map<String, String>) parsed.getOrDefault("tags", new HashMap<>()));
            entry.setFields((Map<String, Object>) parsed.getOrDefault("fields", new HashMap<>()));
            return entry;
        } catch (Exception e) {
            log.warn("Failed to parse JSON log: {}", e.getMessage());
            return createDefaultEntry(rawLog);
        }
    }

    private LogEntry createDefaultEntry(String rawLog) {
        LogEntry entry = new LogEntry();
        entry.setRawLog(rawLog);
        entry.setTimestamp(LocalDateTime.now());
        entry.setLevel("INFO");
        entry.setMessage(rawLog);
        return entry;
    }
}
