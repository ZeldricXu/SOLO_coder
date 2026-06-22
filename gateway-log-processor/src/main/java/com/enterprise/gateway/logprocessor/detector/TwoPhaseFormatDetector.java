package com.enterprise.gateway.logprocessor.detector;

import com.enterprise.gateway.logprocessor.model.LogEntry;
import com.enterprise.gateway.logprocessor.model.LogFeature;
import com.enterprise.gateway.logprocessor.model.LogFormat;
import com.enterprise.gateway.logprocessor.parser.LogParser;
import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class TwoPhaseFormatDetector {

    private final FastFeatureExtractor featureExtractor;

    private final SingleBytePrefixFilter byteFilter;

    private final List<LogParser> allParsers;

    @Getter
    private final AtomicLong totalCalls = new AtomicLong(0);

    @Getter
    private final AtomicLong fastPathHits = new AtomicLong(0);

    @Getter
    private final AtomicLong slowPathFallbacks = new AtomicLong(0);

    public TwoPhaseFormatDetector(FastFeatureExtractor featureExtractor,
                                  SingleBytePrefixFilter byteFilter) {
        if (featureExtractor == null) {
            throw new IllegalArgumentException("featureExtractor cannot be null");
        }
        if (byteFilter == null) {
            throw new IllegalArgumentException("byteFilter cannot be null");
        }
        this.featureExtractor = featureExtractor;
        this.byteFilter = byteFilter;
        this.allParsers = byteFilter.getAllParsers();
    }

    public LogFormat detectFormat(String line) {
        if (line == null || line.isEmpty()) {
            return LogFormat.UNKNOWN;
        }

        totalCalls.incrementAndGet();

        byte[] lineBytes = line.getBytes(StandardCharsets.UTF_8);
        LogFeature feature = featureExtractor.extract(lineBytes, 0, lineBytes.length);

        List<LogParser> candidates = byteFilter.getCandidateParsers(feature.getFirstByte());

        for (LogParser parser : candidates) {
            if (parser.tryParse(line)) {
                fastPathHits.incrementAndGet();
                return parser.getFormat();
            }
        }

        slowPathFallbacks.incrementAndGet();
        for (LogParser parser : allParsers) {
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

        totalCalls.incrementAndGet();

        byte[] lineBytes = line.getBytes(StandardCharsets.UTF_8);
        LogFeature feature = featureExtractor.extract(lineBytes, 0, lineBytes.length);

        List<LogParser> candidates = byteFilter.getCandidateParsers(feature.getFirstByte());

        for (LogParser parser : candidates) {
            if (parser.tryParse(line)) {
                fastPathHits.incrementAndGet();
                return parser.parse(line);
            }
        }

        slowPathFallbacks.incrementAndGet();
        for (LogParser parser : allParsers) {
            if (parser.tryParse(line)) {
                return parser.parse(line);
            }
        }

        return null;
    }

    public void resetCounters() {
        totalCalls.set(0);
        fastPathHits.set(0);
        slowPathFallbacks.set(0);
    }

    public double getFastPathHitRate() {
        long total = totalCalls.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) fastPathHits.get() / total;
    }

}
