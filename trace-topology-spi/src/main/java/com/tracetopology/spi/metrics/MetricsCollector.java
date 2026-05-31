package com.tracetopology.spi.metrics;

import java.time.Duration;
import java.util.Map;

public interface MetricsCollector {

    void incrementCounter(String name, Map<String, String> tags);

    void incrementCounter(String name, long amount, Map<String, String> tags);

    void recordGauge(String name, double value, Map<String, String> tags);

    void recordHistogram(String name, double value, Map<String, String> tags);

    void recordTimer(String name, Duration duration, Map<String, String> tags);

    Map<String, Object> getMetricsSnapshot();
}
