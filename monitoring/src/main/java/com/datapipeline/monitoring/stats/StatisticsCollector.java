package com.datapipeline.monitoring.stats;

import com.datapipeline.common.model.StatisticsSnapshot;
import com.datapipeline.common.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
public class StatisticsCollector {

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> histograms = new ConcurrentHashMap<>();
    private final int maxHistogramSize = 1000;

    public void incrementCounter(String name) {
        counters.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    public void incrementCounter(String name, long delta) {
        counters.computeIfAbsent(name, k -> new LongAdder()).add(delta);
    }

    public void setGauge(String name, long value) {
        gauges.computeIfAbsent(name, k -> new AtomicLong(0)).set(value);
    }

    public void recordHistogram(String name, long value) {
        Deque<Long> deque = histograms.computeIfAbsent(name, k -> new ArrayDeque<>(maxHistogramSize));
        synchronized (deque) {
            if (deque.size() >= maxHistogramSize) {
                deque.pollFirst();
            }
            deque.addLast(value);
        }
    }

    public long getCounter(String name) {
        LongAdder adder = counters.get(name);
        return adder != null ? adder.sum() : 0;
    }

    public long getGauge(String name) {
        AtomicLong gauge = gauges.get(name);
        return gauge != null ? gauge.get() : 0;
    }

    public HistogramStats getHistogramStats(String name) {
        Deque<Long> deque = histograms.get(name);
        if (deque == null || deque.isEmpty()) {
            return HistogramStats.EMPTY;
        }

        List<Long> values;
        synchronized (deque) {
            values = new ArrayList<>(deque);
        }

        Collections.sort(values);
        long sum = values.stream().mapToLong(Long::longValue).sum();
        return HistogramStats.builder()
                .count(values.size())
                .min(values.get(0))
                .max(values.get(values.size() - 1))
                .avg(sum / (double) values.size())
                .p50(percentile(values, 50))
                .p95(percentile(values, 95))
                .p99(percentile(values, 99))
                .sum(sum)
                .build();
    }

    private long percentile(List<Long> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
        index = Math.max(0, Math.min(index, sortedValues.size() - 1));
        return sortedValues.get(index);
    }

    public StatisticsSnapshot snapshot(Map<String, String> dimensions) {
        StatisticsSnapshot snapshot = StatisticsSnapshot.builder()
                .snapshotId(IdGenerator.generate("snap"))
                .timestamp(Instant.now())
                .dimensions(new HashMap<>(dimensions))
                .build();

        for (Map.Entry<String, LongAdder> entry : counters.entrySet()) {
            snapshot.metric("counter_" + entry.getKey(), entry.getValue().sum());
        }

        for (Map.Entry<String, AtomicLong> entry : gauges.entrySet()) {
            snapshot.metric("gauge_" + entry.getKey(), entry.getValue().get());
        }

        return snapshot;
    }

    public StatisticsSnapshot snapshot() {
        return snapshot(Collections.emptyMap());
    }

    public void reset() {
        counters.clear();
        gauges.clear();
        histograms.clear();
    }

}
