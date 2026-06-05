package com.datateam.loganalyzer.parser;

import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.model.LogLevel;
import com.datateam.loganalyzer.util.JsonUtils;
import com.datateam.loganalyzer.util.TimeUtils;

import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonLogParser extends AbstractLogParser {

    private String timestampField = "@timestamp";
    private String levelField = "level";
    private String messageField = "message";
    private String serviceField = "service";
    private String loggerField = "logger";
    private String threadField = "thread";
    private String hostField = "host";
    private String stackTraceField = "stack_trace";

    public JsonLogParser() {
    }

    public JsonLogParser(String timestampField, String levelField, String messageField) {
        this.timestampField = timestampField;
        this.levelField = levelField;
        this.messageField = messageField;
    }

    @Override
    protected Pattern getLinePattern() {
        return null;
    }

    @Override
    protected LogEvent extractFields(Matcher matcher, String rawLine) {
        return null;
    }

    @Override
    public LogEvent parse(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        Map<String, Object> json = JsonUtils.parseJsonLine(line.trim());
        if (json == null) {
            return null;
        }

        LogEvent event = new LogEvent();
        event.setRawLine(line);

        Object tsObj = json.get(timestampField);
        if (tsObj != null) {
            Instant ts = TimeUtils.parseTimestamp(tsObj.toString());
            event.setTimestamp(ts != null ? ts : Instant.now());
        } else {
            event.setTimestamp(Instant.now());
        }

        Object levelObj = json.get(levelField);
        if (levelObj != null) {
            event.setLevel(LogLevel.fromString(levelObj.toString()));
        } else {
            event.setLevel(LogLevel.UNKNOWN);
        }

        Object msgObj = json.get(messageField);
        if (msgObj != null) {
            event.setMessage(msgObj.toString());
        }

        Object serviceObj = json.get(serviceField);
        if (serviceObj != null) {
            event.setService(serviceObj.toString());
        } else if (serviceName != null) {
            event.setService(serviceName);
        }

        Object loggerObj = json.get(loggerField);
        if (loggerObj != null) {
            event.setLogger(loggerObj.toString());
        }

        Object threadObj = json.get(threadField);
        if (threadObj != null) {
            event.setThread(threadObj.toString());
        }

        Object hostObj = json.get(hostField);
        if (hostObj != null) {
            event.setHost(hostObj.toString());
        }

        Object stackObj = json.get(stackTraceField);
        if (stackObj != null) {
            event.setStackTrace(stackObj.toString());
        }

        for (Map.Entry<String, Object> entry : json.entrySet()) {
            String key = entry.getKey();
            if (!key.equals(timestampField) && !key.equals(levelField) &&
                !key.equals(messageField) && !key.equals(serviceField) &&
                !key.equals(loggerField) && !key.equals(threadField) &&
                !key.equals(hostField) && !key.equals(stackTraceField)) {
                event.addField(key, entry.getValue() != null ? entry.getValue().toString() : null);
            }
        }

        event.setErrorType(event.extractErrorType());

        return event;
    }

    @Override
    public LogFormat getFormat() {
        return LogFormat.JSON_LINES;
    }

    public void setTimestampField(String timestampField) {
        this.timestampField = timestampField;
    }

    public void setLevelField(String levelField) {
        this.levelField = levelField;
    }

    public void setMessageField(String messageField) {
        this.messageField = messageField;
    }

    public void setServiceField(String serviceField) {
        this.serviceField = serviceField;
    }

    public void setLoggerField(String loggerField) {
        this.loggerField = loggerField;
    }

    public void setThreadField(String threadField) {
        this.threadField = threadField;
    }

    public void setHostField(String hostField) {
        this.hostField = hostField;
    }

    public void setStackTraceField(String stackTraceField) {
        this.stackTraceField = stackTraceField;
    }
}
