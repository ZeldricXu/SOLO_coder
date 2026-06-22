package com.enterprise.gateway.logprocessor.parser;

import com.enterprise.gateway.logprocessor.model.LogEntry;
import com.enterprise.gateway.logprocessor.model.LogFormat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NginxLogParser implements LogParser {

    private static final Pattern PATTERN = Pattern.compile(
            "^(\\S+) - - \\[([^\\]]+)\\] \"(\\S+) (\\S+) [^\"]+\" (\\d+) (\\d+) .*$");

    private static final DateTimeFormatter NGINX_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

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
            String timestampStr = matcher.group(2);
            LocalDateTime dateTime = LocalDateTime.parse(timestampStr, NGINX_DATE_FORMAT);
            out.timestamp(dateTime.toInstant(ZoneOffset.UTC).toEpochMilli());
            out.service("nginx");
            out.level("INFO");
            out.method(matcher.group(3));
            out.path(matcher.group(4));
            out.statusCode(matcher.group(5));
            out.duration(matcher.group(6));
            out.message(line);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public LogFormat getFormat() {
        return LogFormat.NGINX;
    }

    @Override
    public byte getFirstByte() {
        return '0';
    }
}
