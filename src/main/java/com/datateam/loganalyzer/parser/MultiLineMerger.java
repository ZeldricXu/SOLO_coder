package com.datateam.loganalyzer.parser;

import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.model.LogLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class MultiLineMerger {

    private static final Pattern STACK_TRACE_LINE = Pattern.compile(
        "^\\s+(at\\s+[a-zA-Z0-9$.]+\\([^)]*\\)|Caused by:\\s+|\\.\\.\\.\\s+\\d+\\s+more)"
    );

    private static final Pattern NEW_LOG_ENTRY = Pattern.compile(
        "^(\\d{4}[-/]\\d{2}[-/]\\d{2}|[A-Z][a-z]{2}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2}|\\{)"
    );

    private final LogParser parser;
    private final StringBuilder currentStackTrace;
    private LogEvent currentEvent;

    public MultiLineMerger(LogParser parser) {
        this.parser = parser;
        this.currentStackTrace = new StringBuilder();
    }

    public List<LogEvent> processLines(List<String> lines) {
        List<LogEvent> events = new ArrayList<>();
        for (String line : lines) {
            LogEvent event = processLine(line);
            if (event != null) {
                events.add(event);
            }
        }
        LogEvent last = flush();
        if (last != null) {
            events.add(last);
        }
        return events;
    }

    public LogEvent processLine(String line) {
        if (line == null) {
            return flush();
        }

        boolean isStackTraceLine = STACK_TRACE_LINE.matcher(line).matches();
        boolean isNewEntry = NEW_LOG_ENTRY.matcher(line.trim()).find();

        if (isStackTraceLine && currentEvent != null) {
            if (currentStackTrace.length() > 0) {
                currentStackTrace.append("\n");
            }
            currentStackTrace.append(line);
            return null;
        }

        if (isNewEntry || !isStackTraceLine) {
            LogEvent completed = flush();
            currentEvent = parser.parse(line);
            return completed;
        }

        if (currentEvent != null && currentEvent.getMessage() != null) {
            currentEvent.setMessage(currentEvent.getMessage() + " " + line.trim());
        }

        return null;
    }

    public LogEvent flush() {
        if (currentEvent == null) {
            return null;
        }

        if (currentStackTrace.length() > 0) {
            currentEvent.setStackTrace(currentStackTrace.toString());
            if (currentEvent.getErrorType() == null) {
                currentEvent.setErrorType(currentEvent.extractErrorType());
            }
            if (currentEvent.getLevel() == null || currentEvent.getLevel() == LogLevel.UNKNOWN) {
                currentEvent.setLevel(LogLevel.ERROR);
            }
            currentStackTrace.setLength(0);
        }

        LogEvent result = currentEvent;
        currentEvent = null;
        return result;
    }

    public static List<String> mergeLines(List<String> rawLines) {
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : rawLines) {
            if (line == null) continue;

            boolean isStackTraceLine = STACK_TRACE_LINE.matcher(line).matches();
            boolean isNewEntry = NEW_LOG_ENTRY.matcher(line.trim()).find();

            if (isNewEntry && current.length() > 0) {
                merged.add(current.toString());
                current.setLength(0);
                current.append(line);
            } else if (isStackTraceLine) {
                if (current.length() > 0) {
                    current.append("\n").append(line);
                } else {
                    current.append(line);
                }
            } else {
                if (current.length() > 0) {
                    merged.add(current.toString());
                }
                current.setLength(0);
                current.append(line);
            }
        }

        if (current.length() > 0) {
            merged.add(current.toString());
        }

        return merged;
    }
}
