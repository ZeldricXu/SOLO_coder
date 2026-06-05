package com.datateam.loganalyzer.parser;

import com.datateam.loganalyzer.model.LogEvent;

import java.util.List;
import java.util.stream.Stream;

public interface LogParser {
    LogEvent parse(String line);
    List<LogEvent> parseAll(List<String> lines);
    Stream<LogEvent> stream(Stream<String> lines);
    LogFormat getFormat();
}
