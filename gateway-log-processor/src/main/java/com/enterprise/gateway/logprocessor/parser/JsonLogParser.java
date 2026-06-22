package com.enterprise.gateway.logprocessor.parser;

import com.enterprise.gateway.logprocessor.model.LogEntry;
import com.enterprise.gateway.logprocessor.model.LogFormat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class JsonLogParser implements LogParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean tryParse(String line, LogEntry.LogEntryBuilder out) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        if (line.charAt(0) != '{') {
            return false;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
            out.timestamp(getLongValue(map, "timestamp"));
            out.service(getStringValue(map, "service"));
            out.level(getStringValue(map, "level"));
            out.message(getStringValue(map, "message"));
            out.traceId(getStringValue(map, "traceId"));
            out.statusCode(getStringValue(map, "statusCode"));
            out.method(getStringValue(map, "method"));
            out.path(getStringValue(map, "path"));
            out.duration(getStringValue(map, "duration"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public LogFormat getFormat() {
        return LogFormat.JSON;
    }

    @Override
    public byte getFirstByte() {
        return '{';
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
