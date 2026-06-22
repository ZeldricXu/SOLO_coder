package com.enterprise.gateway.logprocessor.detector;

import com.enterprise.gateway.logprocessor.model.LogEntry;
import com.enterprise.gateway.logprocessor.model.LogFormat;
import com.enterprise.gateway.logprocessor.parser.LogParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FormatDetector {

    private final List<LogParser> parsers;

    public FormatDetector(List<LogParser> parsers) {
        if (parsers == null || parsers.isEmpty()) {
            throw new IllegalArgumentException("parsers cannot be null or empty");
        }
        this.parsers = Collections.unmodifiableList(new ArrayList<>(parsers));
    }

    public LogFormat detectFormat(String line) {
        if (line == null || line.isEmpty()) {
            return LogFormat.UNKNOWN;
        }

        for (LogParser parser : parsers) {
            if (parser.tryParse(line)) {
                return parser.getFormat();
            }
        }

        return LogFormat.UNKNOWN;
    }

    public LogEntry parse(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }

        for (LogParser parser : parsers) {
            if (parser.tryParse(line)) {
                return parser.parse(line);
            }
        }

        return null;
    }

    public List<LogParser> getParsers() {
        return parsers;
    }

}
