package com.logmanager.service.impl;

import com.logmanager.service.MonitoringService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringServiceImpl implements MonitoringService {

    private final MeterRegistry meterRegistry;

    private final Map<String, AtomicLong> successCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> errorCounters = new ConcurrentHashMap<>();
    private final Map<String, Timer> latencyTimers = new ConcurrentHashMap<>();

    @Override
    public Mono<Map<String, Object>> getSystemMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("timestamp", System.currentTimeMillis());
        metrics.put("jvm", getJvmMetrics().block());
        metrics.put("health", getHealthStatus().block());
        return Mono.just(metrics);
    }

    @Override
    public Mono<Map<String, Object>> getServiceMetrics(String serviceName) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("serviceName", serviceName);
        metrics.put("successCount", successCounters.getOrDefault(serviceName, new AtomicLong(0)).get());
        metrics.put("errorCount", errorCounters.getOrDefault(serviceName, new AtomicLong(0)).get());
        metrics.put("timestamp", System.currentTimeMillis());
        return Mono.just(metrics);
    }

    @Override
    public Mono<Map<String, Object>> getJvmMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        metrics.put("uptimeMs", runtimeBean.getUptime());
        metrics.put("startTime", runtimeBean.getStartTime());
        metrics.put("heapMemoryUsed", memoryBean.getHeapMemoryUsage().getUsed());
        metrics.put("heapMemoryMax", memoryBean.getHeapMemoryUsage().getMax());
        metrics.put("nonHeapMemoryUsed", memoryBean.getNonHeapMemoryUsage().getUsed());
        metrics.put("threadCount", threadBean.getThreadCount());
        metrics.put("peakThreadCount", threadBean.getPeakThreadCount());
        metrics.put("daemonThreadCount", threadBean.getDaemonThreadCount());

        Runtime runtime = Runtime.getRuntime();
        metrics.put("availableProcessors", runtime.availableProcessors());
        metrics.put("freeMemory", runtime.freeMemory());
        metrics.put("totalMemory", runtime.totalMemory());
        metrics.put("maxMemory", runtime.maxMemory());

        return Mono.just(metrics);
    }

    @Override
    public Mono<Map<String, Object>> getDatabaseMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("status", "healthy");
        metrics.put("timestamp", System.currentTimeMillis());
        return Mono.just(metrics);
    }

    @Override
    public Mono<Map<String, Object>> getCacheMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("caffeineSize", 10000);
        metrics.put("caffeineHitRate", 0.95);
        metrics.put("timestamp", System.currentTimeMillis());
        return Mono.just(metrics);
    }

    @Override
    public void recordLatency(String operation, long latencyMs) {
        Timer timer = latencyTimers.computeIfAbsent(operation,
                k -> Timer.builder("operation.latency")
                        .tag("operation", operation)
                        .register(meterRegistry));
        timer.record(latencyMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordError(String operation) {
        errorCounters.computeIfAbsent(operation, k -> new AtomicLong(0)).incrementAndGet();
        meterRegistry.counter("operation.errors", "operation", operation).increment();
    }

    @Override
    public void recordSuccess(String operation) {
        successCounters.computeIfAbsent(operation, k -> new AtomicLong(0)).incrementAndGet();
        meterRegistry.counter("operation.success", "operation", operation).increment();
    }

    @Override
    public Mono<Map<String, Object>> getHealthStatus() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());

        Map<String, Object> components = new HashMap<>();
        components.put("database", Map.of("status", "UP"));
        components.put("cache", Map.of("status", "UP"));
        components.put("jvm", Map.of("status", "UP"));
        health.put("components", components);

        return Mono.just(health);
    }
}
