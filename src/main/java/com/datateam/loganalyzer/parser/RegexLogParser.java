package com.datateam.loganalyzer.parser;

import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.model.LogLevel;
import com.datateam.loganalyzer.util.TimeUtils;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RegexLogParser extends AbstractLogParser {

    private Pattern linePattern;
    private int timestampGroup = 1;
    private int levelGroup = 2;
    private int messageGroup = 3;
    private int serviceGroup = -1;
    private int loggerGroup = -1;
    private int threadGroup = -1;

    public RegexLogParser(String regex) {
        this.linePattern = Pattern.compile(regex);
    }

    public RegexLogParser(String regex, int timestampGroup, int levelGroup, int messageGroup) {
        this.linePattern = Pattern.compile(regex);
        this.timestampGroup = timestampGroup;
        this.levelGroup = levelGroup;
        this.messageGroup = messageGroup;
    }

    @Override
    protected Pattern getLinePattern() {
        return linePattern;
    }

    @Override
    protected LogEvent extractFields(Matcher matcher, String rawLine) {
        LogEvent event = new LogEvent();

        if (timestampGroup > 0 && timestampGroup <= matcher.groupCount()) {
            String tsStr = matcher.group(timestampGroup);
            if (tsStr != null) {
                Instant ts = TimeUtils.parseTimestamp(tsStr);
                event.setTimestamp(ts != null ? ts : Instant.now());
            }
        }
        if (event.getTimestamp() == null) {
            event.setTimestamp(Instant.now());
        }

        if (levelGroup > 0 && levelGroup <= matcher.groupCount()) {
            String levelStr = matcher.group(levelGroup);
            if (levelStr != null) {
                event.setLevel(LogLevel.fromString(levelStr));
            }
        }
        if (event.getLevel() == null) {
            event.setLevel(LogLevel.UNKNOWN);
        }

        if (messageGroup > 0 && messageGroup <= matcher.groupCount()) {
            String msgStr = matcher.group(messageGroup);
            event.setMessage(msgStr != null ? msgStr : rawLine);
        } else {
            event.setMessage(rawLine);
        }

        if (serviceGroup > 0 && serviceGroup <= matcher.groupCount()) {
            event.setService(matcher.group(serviceGroup));
        } else if (serviceName != null) {
            event.setService(serviceName);
        }

        if (loggerGroup > 0 && loggerGroup <= matcher.groupCount()) {
            event.setLogger(matcher.group(loggerGroup));
        }

        if (threadGroup > 0 && threadGroup <= matcher.groupCount()) {
            event.setThread(matcher.group(threadGroup));
        }

        event.setErrorType(event.extractErrorType());

        return event;
    }

    @Override
    public LogFormat getFormat() {
        return LogFormat.CUSTOM_REGEX;
    }

    public void setTimestampGroup(int timestampGroup) {
        this.timestampGroup = timestampGroup;
    }

    public void setLevelGroup(int levelGroup) {
        this.levelGroup = levelGroup;
    }

    public void setMessageGroup(int messageGroup) {
        this.messageGroup = messageGroup;
    }

    public void setServiceGroup(int serviceGroup) {
        this.serviceGroup = serviceGroup;
    }

    public void setLoggerGroup(int loggerGroup) {
        this.loggerGroup = loggerGroup;
    }

    public void setThreadGroup(int threadGroup) {
        this.threadGroup = threadGroup;
    }
}
