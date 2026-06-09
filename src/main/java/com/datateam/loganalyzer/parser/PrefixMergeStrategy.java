package com.datateam.loganalyzer.parser;

import java.util.regex.Pattern;

public class PrefixMergeStrategy implements MultilineMergeStrategy {

    private final String prefixPattern;
    private final Pattern compiledPattern;
    private final Pattern newEntryPattern;
    private final int minPrefixLength;

    public PrefixMergeStrategy(String prefixPattern) {
        this(prefixPattern, 3);
    }

    public PrefixMergeStrategy(String prefixPattern, int minPrefixLength) {
        this.prefixPattern = prefixPattern;
        this.compiledPattern = Pattern.compile(prefixPattern);
        this.newEntryPattern = Pattern.compile(
            "^(\\d{4}[-/]\\d{2}[-/]\\d{2}|[A-Z][a-z]{2}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2}|\\{)"
        );
        this.minPrefixLength = minPrefixLength;
    }

    @Override
    public boolean shouldMerge(String currentLine, String previousLine, StringBuilder currentBuffer) {
        if (currentLine == null || previousLine == null) {
            return false;
        }

        if (newEntryPattern.matcher(currentLine.trim()).find()) {
            return false;
        }

        if (currentBuffer.length() == 0) {
            return false;
        }

        if (compiledPattern.matcher(currentLine.trim()).matches()) {
            return true;
        }

        String commonPrefix = getCommonPrefix(previousLine.trim(), currentLine.trim());
        return commonPrefix.length() >= minPrefixLength;
    }

    @Override
    public String merge(String currentLine, StringBuilder currentBuffer) {
        if (currentBuffer.length() > 0) {
            currentBuffer.append("\n");
        }
        currentBuffer.append(currentLine);
        return currentBuffer.toString();
    }

    private String getCommonPrefix(String a, String b) {
        int minLen = Math.min(a.length(), b.length());
        int i = 0;
        while (i < minLen && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return a.substring(0, i);
    }

    @Override
    public String getName() {
        return "prefix";
    }
}
