package com.datateam.loganalyzer.parser;

import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.model.LogLevel;
import com.datateam.loganalyzer.util.TimeUtils;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SyslogParser extends AbstractLogParser {

    private static final Pattern SYSLOG_3164_PATTERN = Pattern.compile(
        "^<(\\d+)>([A-Z][a-z]{2}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+" +
        "(\\S+)\\s+" +
        "(\\S+)(?:\\[\\d+\\])?\\s*:\\s*" +
        "(.*)$"
    );

    private static final Pattern SYSLOG_5424_PATTERN = Pattern.compile(
        "^<(\\d+)>\\d\\s+" +
        "(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2}))\\s+" +
        "(\\S+)\\s+" +
        "(\\S+)\\s+" +
        "(\\S+)\\s+" +
        "(\\S+)\\s+" +
        "(.*)$"
    );

    private Pattern linePattern;

    public SyslogParser() {
        this.linePattern = SYSLOG_3164_PATTERN;
    }

    public SyslogParser(Pattern pattern) {
        this.linePattern = pattern;
    }

    @Override
    protected Pattern getLinePattern() {
        return linePattern;
    }

    @Override
    protected LogEvent extractFields(Matcher matcher, String rawLine) {
        LogEvent event = new LogEvent();

        if (matcher.pattern() == SYSLOG_3164_PATTERN) {
            String pri = matcher.group(1);
            int severity = Integer.parseInt(pri) % 8;
            event.setLevel(mapSyslogSeverity(severity));

            Instant ts = TimeUtils.parseTimestamp(matcher.group(2));
            event.setTimestamp(ts != null ? ts : Instant.now());

            event.setHost(matcher.group(3).trim());
            event.setLogger(matcher.group(4).trim());
            event.setMessage(matcher.group(5).trim());
        } else if (matcher.pattern() == SYSLOG_5424_PATTERN) {
            String pri = matcher.group(1);
            int severity = Integer.parseInt(pri) % 8;
            event.setLevel(mapSyslogSeverity(severity));

            Instant ts = TimeUtils.parseTimestamp(matcher.group(2));
            event.setTimestamp(ts != null ? ts : Instant.now());

            event.setHost(matcher.group(3).trim());
            event.setService(matcher.group(4).trim());
            event.setLogger(matcher.group(5).trim());
            event.setMessage(matcher.group(7).trim());
        }

        if (serviceName != null) {
            event.setService(serviceName);
        }

        return event;
    }

    private LogLevel mapSyslogSeverity(int severity) {
        switch (severity) {
            case 0: case 1: case 2: return LogLevel.FATAL;
            case 3: return LogLevel.ERROR;
            case 4: return LogLevel.WARN;
            case 5: case 6: return LogLevel.INFO;
            case 7: return LogLevel.DEBUG;
            default: return LogLevel.UNKNOWN;
        }
    }

    @Override
    public LogFormat getFormat() {
        return LogFormat.SYSLOG;
    }
}
