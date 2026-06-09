package com.datateam.loganalyzer.parser;

import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.model.LogLevel;

import java.util.ArrayList;
import java.util.List;

public class MultilineAggregator {

    public enum MergeStrategyType {
        STACK_TRACE,
        PREFIX,
        XML_JSON,
        AUTO
    }

    private final LogParser parser;
    private final List<MultilineMergeStrategy> strategies;
    private final StringBuilder currentBuffer;
    private String previousLine;
    private LogEvent currentEvent;

    public MultilineAggregator(LogParser parser) {
        this(parser, MergeStrategyType.STACK_TRACE);
    }

    public MultilineAggregator(LogParser parser, MergeStrategyType strategyType) {
        this.parser = parser;
        this.strategies = new ArrayList<>();
        this.currentBuffer = new StringBuilder();

        switch (strategyType) {
            case STACK_TRACE:
                strategies.add(new StackTraceMergeStrategy());
                break;
            case PREFIX:
                strategies.add(new PrefixMergeStrategy("^\\s+"));
                break;
            case XML_JSON:
                strategies.add(new XmlJsonMergeStrategy());
                break;
            case AUTO:
            default:
                strategies.add(new StackTraceMergeStrategy());
                strategies.add(new XmlJsonMergeStrategy());
                strategies.add(new PrefixMergeStrategy("^\\s+"));
                break;
        }
    }

    public MultilineAggregator(LogParser parser, List<MultilineMergeStrategy> strategies) {
        this.parser = parser;
        this.strategies = strategies != null ? strategies : new ArrayList<>();
        this.currentBuffer = new StringBuilder();
    }

    public void addStrategy(MultilineMergeStrategy strategy) {
        if (strategy != null) {
            strategies.add(strategy);
        }
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

        boolean shouldMerge = false;
        MultilineMergeStrategy matchingStrategy = null;

        for (MultilineMergeStrategy strategy : strategies) {
            if (strategy.shouldMerge(line, previousLine, currentBuffer)) {
                shouldMerge = true;
                matchingStrategy = strategy;
                break;
            }
        }

        if (shouldMerge && currentEvent != null) {
            if (matchingStrategy != null) {
                matchingStrategy.merge(line, currentBuffer);
            } else {
                if (currentBuffer.length() > 0) {
                    currentBuffer.append("\n");
                }
                currentBuffer.append(line);
            }
            previousLine = line;
            return null;
        }

        LogEvent completed = flush();
        currentEvent = parser.parse(line);
        previousLine = line;

        if (currentEvent != null && currentBuffer.length() == 0) {
            currentBuffer.append(line);
        }

        return completed;
    }

    public LogEvent flush() {
        if (currentEvent == null) {
            return null;
        }

        if (currentBuffer.length() > 0) {
            String fullContent = currentBuffer.toString();
            int firstNewline = fullContent.indexOf('\n');
            if (firstNewline > 0) {
                String stackTrace = fullContent.substring(firstNewline + 1);
                if (!stackTrace.isEmpty()) {
                    currentEvent.setStackTrace(stackTrace);
                    if (currentEvent.getErrorType() == null) {
                        currentEvent.setErrorType(currentEvent.extractErrorType());
                    }
                    if (currentEvent.getLevel() == null || currentEvent.getLevel() == LogLevel.UNKNOWN) {
                        currentEvent.setLevel(LogLevel.ERROR);
                    }
                }
            }
            currentBuffer.setLength(0);
        }

        for (MultilineMergeStrategy strategy : strategies) {
            if (strategy instanceof XmlJsonMergeStrategy) {
                ((XmlJsonMergeStrategy) strategy).reset();
            }
        }

        LogEvent result = currentEvent;
        currentEvent = null;
        return result;
    }

    public static List<String> mergeLines(List<String> rawLines) {
        return mergeLines(rawLines, MergeStrategyType.AUTO);
    }

    public static List<String> mergeLines(List<String> rawLines, MergeStrategyType strategyType) {
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String previous = null;

        List<MultilineMergeStrategy> strategies = new ArrayList<>();
        switch (strategyType) {
            case STACK_TRACE:
                strategies.add(new StackTraceMergeStrategy());
                break;
            case PREFIX:
                strategies.add(new PrefixMergeStrategy("^\\s+"));
                break;
            case XML_JSON:
                strategies.add(new XmlJsonMergeStrategy());
                break;
            case AUTO:
            default:
                strategies.add(new StackTraceMergeStrategy());
                strategies.add(new XmlJsonMergeStrategy());
                strategies.add(new PrefixMergeStrategy("^\\s+"));
                break;
        }

        for (String line : rawLines) {
            if (line == null) continue;

            boolean shouldMerge = false;
            for (MultilineMergeStrategy strategy : strategies) {
                if (strategy.shouldMerge(line, previous, current)) {
                    shouldMerge = true;
                    break;
                }
            }

            if (shouldMerge) {
                if (current.length() > 0) {
                    current.append("\n");
                }
                current.append(line);
            } else {
                if (current.length() > 0) {
                    merged.add(current.toString());
                }
                current.setLength(0);
                current.append(line);
            }
            previous = line;
        }

        if (current.length() > 0) {
            merged.add(current.toString());
        }

        return merged;
    }

    public LogParser getParser() {
        return parser;
    }

    public List<MultilineMergeStrategy> getStrategies() {
        return strategies;
    }
}
