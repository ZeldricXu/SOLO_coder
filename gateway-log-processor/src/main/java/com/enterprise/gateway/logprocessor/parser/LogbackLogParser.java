package com.enterprise.gateway.logprocessor.parser;

import com.enterprise.gateway.logprocessor.model.LogEntry;
import com.enterprise.gateway.logprocessor.model.LogFormat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogbackLogParser implements LogParser {

    private static final Pattern PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) \\[(.*?)\\] (\\w+) (.*?) - (.*)$");

    private static final DateTimeFormatter LOGBACK_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public boolean tryParse(String line, LogEntry.LogEntryBuilder out) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        if (!Character.isDigit(line.charAt(0))) {
            return false;
        }
        Matcher matcher = PATTERN.matcher(line);
        if (!matcher.matches()) {
            return false;
        }
        try {
            String timestampStr = matcher.group(1);
            LocalDateTime dateTime = LocalDateTime.parse(timestampStr, LOGBACK_DATE_FORMAT);
            out.timestamp(dateTime.toInstant(ZoneOffset.UTC).toEpochMilli());
            out.level(matcher.group(3));
            out.service(matcher.group(4));
            out.message(matcher.group(5));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public LogFormat getFormat() {
        return LogFormat.LOGBACK;
    }

    @Override
    public byte getFirstByte() {
        return '0';
    }
}
