package com.solocoder.platform.monitor.service.impl;

import com.solocoder.platform.monitor.model.MetricDataPoint;
import com.solocoder.platform.monitor.model.PerformanceSnapshot;
import com.solocoder.platform.monitor.service.MetricsCollector;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.management.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsCollectorImpl implements MetricsCollector {

    private final MeterRegistry meterRegistry;
    private final Map<String, List<MetricDataPoint>> metricStore = new ConcurrentHashMap<>();
    private final List<PerformanceSnapshot> performanceHistory = new CopyOnWriteArrayList<>();
    private static final int MAX_HISTORY = 1000;

    @Override
    public void record(MetricDataPoint dataPoint) {
        metricStore.computeIfAbsent(dataPoint.getMetricName(), k -> new CopyOnWriteArrayList<>())
                .add(dataPoint);
        io.micrometer.core.instrument.Counter.builder("platform_custom_metric")
                .tag("name", dataPoint.getMetricName())
                .register(meterRegistry).increment(dataPoint.getValue());
        log.debug("Metric recorded: name={}, value={}", dataPoint.getMetricName(), dataPoint.getValue());
    }

    @Override
    public List<MetricDataPoint> query(String metricName, long startTimestamp, long endTimestamp) {
        List<MetricDataPoint> points = metricStore.getOrDefault(metricName, List.of());
        return points.stream()
                .filter(p -> p.getTimestamp() != null)
                .filter(p -> {
                    long ts = p.getTimestamp().getSecond();
                    return ts >= startTimestamp && ts <= endTimestamp;
                })
                .collect(Collectors.toList());
    }

    @Override
    public Optional<MetricDataPoint> latest(String metricName) {
        List<MetricDataPoint> points = metricStore.get(metricName);
        if (points == null || points.isEmpty()) return Optional.empty();
        return Optional.of(points.get(points.size() - 1));
    }

    @Override
    public PerformanceSnapshot capturePerformance() {
        Runtime runtime = Runtime.getRuntime();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeanList();

        long gcCount = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        long gcTime = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();

        PerformanceSnapshot snapshot = PerformanceSnapshot.builder()
                .cpuUsagePercent(computeCpuUsage())
                .usedMemoryMb((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024))
                .totalMemoryMb(runtime.totalMemory() / (1024 * 1024))
                .activeThreads(threadBean.getThreadCount())
                .gcCount(gcCount)
                .gcTimeMs(gcTime)
                .capturedAt(LocalDateTime.now())
                .build();

        if (performanceHistory.size() >= MAX_HISTORY) {
            performanceHistory.subList(0, performanceHistory.size() - MAX_HISTORY + 1).clear();
        }
        performanceHistory.add(snapshot);

        io.micrometer.core.instrument.Gauge.builder("platform_cpu_usage", snapshot, PerformanceSnapshot::getCpuUsagePercent)
                .register(meterRegistry);
        io.micrometer.core.instrument.Gauge.builder("platform_memory_usage", snapshot, PerformanceSnapshot::getMemoryUsagePercent)
                .register(meterRegistry);

        log.debug("Performance snapshot captured: cpu={}%, memory={}%", snapshot.getCpuUsagePercent(), snapshot.getMemoryUsagePercent());
        return snapshot;
    }

    @Override
    public List<PerformanceSnapshot> getPerformanceHistory(int limit) {
        int size = performanceHistory.size();
        int fromIndex = Math.max(0, size - limit);
        return new ArrayList<>(performanceHistory.subList(fromIndex, size));
    }

    private double computeCpuUsage() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            return sunOs.getProcessCpuLoad() * 100;
        }
        return osBean.getSystemLoadAverage();
    }
}
