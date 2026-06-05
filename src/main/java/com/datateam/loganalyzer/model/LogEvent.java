package com.datateam.loganalyzer.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class LogEvent {
    private Instant timestamp;
    private LogLevel level;
    private String service;
    private String host;
    private String logger;
    private String thread;
    private String message;
    private String stackTrace;
    private String errorType;
    private String rawLine;
    private Map<String, String> fields;

    public LogEvent() {
        this.fields = new HashMap<>();
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public LogLevel getLevel() {
        return level;
    }

    public void setLevel(LogLevel level) {
        this.level = level;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getLogger() {
        return logger;
    }

    public void setLogger(String logger) {
        this.logger = logger;
    }

    public String getThread() {
        return thread;
    }

    public void setThread(String thread) {
        this.thread = thread;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public String getRawLine() {
        return rawLine;
    }

    public void setRawLine(String rawLine) {
        this.rawLine = rawLine;
    }

    public Map<String, String> getFields() {
        return fields;
    }

    public void setFields(Map<String, String> fields) {
        this.fields = fields;
    }

    public void addField(String key, String value) {
        this.fields.put(key, value);
    }

    public String getField(String key) {
        return this.fields.get(key);
    }

    public String extractErrorType() {
        if (errorType != null) return errorType;
        if (message == null) return null;
        int colonIdx = message.indexOf(':');
        if (colonIdx > 0 && colonIdx < 100) {
            String candidate = message.substring(0, colonIdx).trim();
            if (candidate.matches("[a-zA-Z0-9$.]+Exception") ||
                candidate.matches("[a-zA-Z0-9$.]+Error")) {
                return candidate;
            }
        }
        if (stackTrace != null) {
            String[] lines = stackTrace.split("\n");
            for (String line : lines) {
                if (line.matches("\\s*(at\\s+)?[a-zA-Z0-9$.]+(Exception|Error).*")) {
                    int atIdx = line.indexOf("at ");
                    int excIdx = line.indexOf("Exception");
                    int errIdx = line.indexOf("Error");
                    int endIdx = excIdx > 0 ? excIdx + 9 : (errIdx > 0 ? errIdx + 5 : -1);
                    if (endIdx > 0) {
                        int startIdx = atIdx >= 0 ? atIdx + 3 : 0;
                        String type = line.substring(startIdx, endIdx).trim();
                        if (type.contains(".")) {
                            return type.substring(type.lastIndexOf('.') + 1);
                        }
                        return type;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s %s - %s", timestamp, level, service,
            message != null ? (message.length() > 100 ? message.substring(0, 100) + "..." : message) : "");
    }
}
