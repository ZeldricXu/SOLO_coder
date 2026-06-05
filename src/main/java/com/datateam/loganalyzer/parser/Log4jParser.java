package com.datateam.loganalyzer.parser;

import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.model.LogLevel;
import com.datateam.loganalyzer.util.TimeUtils;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Log4jParser extends AbstractLogParser {

    private static final Pattern DEFAULT_LOG4J_PATTERN = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}[.,]?\\d{0,3})\\s+" +
        "([A-Z]+)\\s+" +
        "(?:\\[(.*?)\\]\\s+)?" +
        "(?:\\[(.*?)\\]\\s+)?" +
        "([\\w$.]+)\\s*[-:]\\s*" +
        "(.*)$"
    );

    private Pattern linePattern;

    public Log4jParser() {
        this.linePattern = DEFAULT_LOG4J_PATTERN;
    }

    public Log4jParser(String customPattern) {
        this.linePattern = Pattern.compile(customPattern);
    }

    @Override
    protected Pattern getLinePattern() {
        return linePattern;
    }

    @Override
    protected LogEvent extractFields(Matcher matcher, String rawLine) {
        LogEvent event = new LogEvent();

        String timestampStr = matcher.group(1);
        Instant ts = TimeUtils.parseTimestamp(timestampStr);
        event.setTimestamp(ts != null ? ts : Instant.now());

        event.setLevel(LogLevel.fromString(matcher.group(2)));

        if (matcher.group(3) != null) {
            event.setThread(matcher.group(3).trim());
        }

        if (matcher.group(4) != null) {
            event.setService(matcher.group(4).trim());
        } else if (serviceName != null) {
            event.setService(serviceName);
        }

        event.setLogger(matcher.group(5).trim());
        event.setMessage(matcher.group(6).trim());

        return event;
    }

    @Override
    public LogFormat getFormat() {
        return LogFormat.LOG4J;
    }
}
