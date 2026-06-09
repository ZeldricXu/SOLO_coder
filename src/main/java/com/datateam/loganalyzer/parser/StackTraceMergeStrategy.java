package com.datateam.loganalyzer.parser;

import java.util.regex.Pattern;

public class StackTraceMergeStrategy implements MultilineMergeStrategy {

    private static final Pattern STACK_TRACE_LINE = Pattern.compile(
        "^(\\s+(at\\s+[a-zA-Z0-9$.]+\\([^)]*\\)|\\.\\.\\.\\s+\\d+\\s+more)|Caused by:.*)$"
    );

    private static final Pattern EXCEPTION_CLASS_LINE = Pattern.compile(
        "^([a-zA-Z_$][a-zA-Z\\d_$]*\\.)+[a-zA-Z_$][a-zA-Z\\d_$]*Exception:|" +
        "^([a-zA-Z_$][a-zA-Z\\d_$]*\\.)+[a-zA-Z_$][a-zA-Z\\d_$]*Error:"
    );

    private static final Pattern NEW_LOG_ENTRY = Pattern.compile(
        "^(\\d{4}[-/]\\d{2}[-/]\\d{2}|[A-Z][a-z]{2}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2}|\\{)"
    );

    @Override
    public boolean shouldMerge(String currentLine, String previousLine, StringBuilder currentBuffer) {
        if (currentLine == null) {
            return false;
        }

        boolean isStackTraceLine = STACK_TRACE_LINE.matcher(currentLine).matches();
        boolean isExceptionClassLine = EXCEPTION_CLASS_LINE.matcher(currentLine.trim()).find();
        boolean isNewEntry = NEW_LOG_ENTRY.matcher(currentLine.trim()).find();

        if (isNewEntry) {
            return false;
        }

        if (isStackTraceLine || isExceptionClassLine) {
            return currentBuffer.length() > 0;
        }

        return false;
    }

    @Override
    public String merge(String currentLine, StringBuilder currentBuffer) {
        if (currentBuffer.length() > 0) {
            currentBuffer.append("\n");
        }
        currentBuffer.append(currentLine);
        return currentBuffer.toString();
    }

    @Override
    public String getName() {
        return "stack-trace";
    }
}
