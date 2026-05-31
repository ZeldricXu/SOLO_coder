package com.tracetopology.infrastructure.metrics;

import com.tracetopology.spi.metrics.MetricsCollector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Histogram;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MicrometerMetricsCollector implements MetricsCollector {

    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Histogram> histograms = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    @Override
    public void incrementCounter(String name, Map<String, String> tags) {
        Counter counter = counters.computeIfAbsent(name + tags, k ->
                Counter.builder(name)
                        .tags(Tags.of(tags.entrySet().stream()
                                .map(e -> io.micrometer.core.instrument.Tag.of(e.getKey(), e.getValue()))
                                .toList()))
                        .register(meterRegistry));
        counter.increment();
    }

    @Override
    public void incrementCounter(String name, long amount, Map<String, String> tags) {
        Counter counter = counters.computeIfAbsent(name + tags, k ->
                Counter.builder(name)
                        .tags(Tags.of(tags.entrySet().stream()
                                .map(e -> io.micrometer.core.instrument.Tag.of(e.getKey(), e.getValue()))
                                .toList()))
                        .register(meterRegistry));
        counter.increment(amount);
    }

    @Override
    public void recordGauge(String name, double value, Map<String, String> tags) {
        Gauge.builder(name, () -> value)
                .tags(Tags.of(tags.entrySet().stream()
                        .map(e -> io.micrometer.core.instrument.Tag.of(e.getKey(), e.getValue()))
                        .toList()))
                .register(meterRegistry);
    }

    @Override
    public void recordHistogram(String name, double value, Map<String, String> tags) {
        Histogram histogram = histograms.computeIfAbsent(name + tags, k ->
                Histogram.builder(name)
                        .tags(Tags.of(tags.entrySet().stream()
                                .map(e -> io.micrometer.core.instrument.Tag.of(e.getKey(), e.getValue()))
                                .toList()))
                        .register(meterRegistry));
        histogram.record(value);
    }

    @Override
    public void recordTimer(String name, Duration duration, Map<String, String> tags) {
        Timer timer = timers.computeIfAbsent(name + tags, k ->
                Timer.builder(name)
                        .tags(Tags.of(tags.entrySet().stream()
                                .map(e -> io.micrometer.core.instrument.Tag.of(e.getKey(), e.getValue()))
                                .toList()))
                        .register(meterRegistry));
        timer.record(duration);
    }

    @Override
    public Map<String, Object> getMetricsSnapshot() {
        Map<String, Object> snapshot = new HashMap<>();
        meterRegistry.forEachMeter(meter -> {
            String name = meter.getId().getName();
            meter.measure().forEach(measurement -> {
                snapshot.put(name + "." + measurement.getStatistic().getTagValueRepresentation(),
                        measurement.getValue());
            });
        });
        return snapshot;
    }
}
