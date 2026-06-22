package com.enterprise.gateway.logprocessor.detector;

import com.enterprise.gateway.logprocessor.parser.LogParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SingleBytePrefixFilter {

    private final Map<Byte, List<LogParser>> formatIndex;

    private final List<LogParser> allParsers;

    public SingleBytePrefixFilter(List<LogParser> parsers) {
        if (parsers == null || parsers.isEmpty()) {
            throw new IllegalArgumentException("parsers cannot be null or empty");
        }

        this.allParsers = Collections.unmodifiableList(new ArrayList<>(parsers));
        this.formatIndex = new HashMap<>();

        for (LogParser parser : parsers) {
            if (parser == null) {
                throw new IllegalArgumentException("parser cannot be null");
            }
            Byte firstByte = parser.getFirstByte();
            formatIndex.computeIfAbsent(firstByte, k -> new ArrayList<>()).add(parser);
        }

        formatIndex.replaceAll((k, v) -> Collections.unmodifiableList(v));
    }

    public List<LogParser> getCandidateParsers(byte firstByte) {
        List<LogParser> candidates = formatIndex.get(firstByte);
        return candidates != null ? candidates : Collections.emptyList();
    }

    public List<LogParser> getAllParsers() {
        return allParsers;
    }

}
