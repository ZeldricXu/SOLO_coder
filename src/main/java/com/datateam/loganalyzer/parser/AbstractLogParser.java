package com.datateam.loganalyzer.parser;

import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.model.LogLevel;
import com.datateam.loganalyzer.util.TimeUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class AbstractLogParser implements LogParser {

    protected Pattern timestampPattern;
    protected Pattern levelPattern;
    protected String serviceName;

    protected abstract Pattern getLinePattern();
    protected abstract LogEvent extractFields(Matcher matcher, String rawLine);

    @Override
    public LogEvent parse(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        Pattern pattern = getLinePattern();
        if (pattern == null) {
            return parseGeneric(line);
        }

        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            LogEvent event = extractFields(matcher, line);
            if (event != null) {
                event.setRawLine(line);
                if (event.getErrorType() == null) {
                    event.setErrorType(event.extractErrorType());
                }
                return event;
            }
        }

        return parseGeneric(line);
    }

    protected LogEvent parseGeneric(String line) {
        LogEvent event = new LogEvent();
        event.setRawLine(line);

        Instant ts = extractTimestamp(line);
        if (ts != null) {
            event.setTimestamp(ts);
        } else {
            event.setTimestamp(Instant.now());
        }

        LogLevel level = extractLevel(line);
        event.setLevel(level);

        event.setMessage(line);
        event.setErrorType(event.extractErrorType());
        event.setService(serviceName);

        return event;
    }

    protected Instant extractTimestamp(String line) {
        if (timestampPattern == null) {
            timestampPattern = Pattern.compile(
                "(\\d{4}[-/]\\d{2}[-/]\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:[Z+-]\\d{2}:?\\d{2})?)"
            );
        }
        Matcher m = timestampPattern.matcher(line);
        if (m.find()) {
            return TimeUtils.parseTimestamp(m.group(1));
        }
        return null;
    }

    protected LogLevel extractLevel(String line) {
        if (levelPattern == null) {
            levelPattern = Pattern.compile(
                "\\b(TRACE|DEBUG|INFO|WARNING|WARN|ERROR|ERR|FATAL|CRIT|CRITICAL|SEVERE)\\b",
                Pattern.CASE_INSENSITIVE
            );
        }
        Matcher m = levelPattern.matcher(line);
        if (m.find()) {
            return LogLevel.fromString(m.group(1));
        }
        return LogLevel.UNKNOWN;
    }

    @Override
    public List<LogEvent> parseAll(List<String> lines) {
        return lines.stream()
            .map(this::parse)
            .filter(e -> e != null)
            .collect(Collectors.toList());
    }

    @Override
    public Stream<LogEvent> stream(Stream<String> lines) {
        return lines.map(this::parse).filter(e -> e != null);
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getServiceName() {
        return serviceName;
    }
}
