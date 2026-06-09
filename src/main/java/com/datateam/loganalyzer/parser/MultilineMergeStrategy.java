package com.datateam.loganalyzer.parser;

public interface MultilineMergeStrategy {

    boolean shouldMerge(String currentLine, String previousLine, StringBuilder currentBuffer);

    String merge(String currentLine, StringBuilder currentBuffer);

    String getName();
}
