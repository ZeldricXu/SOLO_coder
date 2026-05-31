package com.datastandard.modules.profiling;

import com.datastandard.modules.profiling.dto.ProfilingReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class CpuSampler {

    private static final int MAX_STACK_DEPTH = 128;

    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong totalSamples = new AtomicLong(0);
    private final Map<String, AtomicLong> samplesByMethod = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> samplesByThread = new ConcurrentHashMap<>();
    private final List<Double> cpuUsageHistory = Collections.synchronizedList(new ArrayList<>());
    private final Map<Long, StackTraceElement[]> stackTraceSnapshots = new ConcurrentHashMap<>();

    private Thread samplerThread;
    private int samplingIntervalMs = 10;
    private long startTimeNanos;

    public synchronized void start(int intervalMs) {
        if (running.compareAndSet(false, true)) {
            this.samplingIntervalMs = intervalMs;
            this.totalSamples.set(0);
            this.samplesByMethod.clear();
            this.samplesByThread.clear();
            this.cpuUsageHistory.clear();
            this.stackTraceSnapshots.clear();
            this.startTimeNanos = System.nanoTime();

            samplerThread = new Thread(this::samplingLoop, "cpu-sampler");
            samplerThread.setDaemon(true);
            samplerThread.start();

            log.info("CPU Sampler started with interval {}ms", intervalMs);
        }
    }

    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            if (samplerThread != null) {
                samplerThread.interrupt();
                try {
                    samplerThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            log.info("CPU Sampler stopped. Total samples: {}", totalSamples.get());
        }
    }

    private void samplingLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                sampleCpu();
                Thread.sleep(samplingIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in CPU sampling", e);
            }
        }
    }

    private void sampleCpu() {
        long sampleTime = System.nanoTime();

        double processCpuLoad = getProcessCpuLoad();
        cpuUsageHistory.add(processCpuLoad);
        if (cpuUsageHistory.size() > 10000) {
            cpuUsageHistory.removeFirst();
        }

        long[] threadIds = threadMXBean.getAllThreadIds();
        for (long threadId : threadIds) {
            Thread.State state = threadMXBean.getThreadInfo(threadId, 0).getThreadState();
            if (state == Thread.State.RUNNABLE) {
                StackTraceElement[] stackTrace = threadMXBean.getThreadInfo(threadId, MAX_STACK_DEPTH).getStackTrace();
                if (stackTrace.length > 0) {
                    String threadName = threadMXBean.getThreadInfo(threadId, 0).getThreadName();
                    samplesByThread.computeIfAbsent(threadName, k -> new AtomicLong(0)).incrementAndGet();
                    stackTraceSnapshots.put(threadId, stackTrace);

                    for (StackTraceElement element : stackTrace) {
                        String methodKey = element.getClassName() + "." + element.getMethodName();
                        samplesByMethod.computeIfAbsent(methodKey, k -> new AtomicLong(0)).incrementAndGet();
                    }
                }
            }
        }

        totalSamples.incrementAndGet();
    }

    private double getProcessCpuLoad() {
        try {
            com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            return osBean.getProcessCpuLoad() * 100;
        } catch (Exception e) {
            return -1;
        }
    }

    public ProfilingReport.CpuReport buildReport() {
        long total = totalSamples.get();
        List<Double> history = new ArrayList<>(cpuUsageHistory);
        Collections.sort(history);

        List<ProfilingReport.HotMethod> hotMethods = samplesByMethod.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(20)
                .map(entry -> {
                    String[] parts = entry.getKey().split("\\.", 2);
                    String className = parts[0];
                    String methodName = parts.length > 1 ? parts[1] : "";
                    String packageName = className.contains(".") ?
                            className.substring(0, className.lastIndexOf('.')) : "";
                    return ProfilingReport.HotMethod.builder()
                            .className(className)
                            .methodName(methodName)
                            .packageName(packageName)
                            .samples(entry.getValue().get())
                            .percentage(total > 0 ? (entry.getValue().get() * 100.0 / total) : 0)
                            .build();
                })
                .toList();

        List<ProfilingReport.HotThread> hotThreads = samplesByThread.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(10)
                .map(entry -> ProfilingReport.HotThread.builder()
                        .threadName(entry.getKey())
                        .samples(entry.getValue().get())
                        .percentage(total > 0 ? (entry.getValue().get() * 100.0 / total) : 0)
                        .build())
                .toList();

        Map<String, Long> samplesByThreadMap = new HashMap<>();
        samplesByThread.forEach((k, v) -> samplesByThreadMap.put(k, v.get()));

        return ProfilingReport.CpuReport.builder()
                .totalSamples(total)
                .averageCpuUsage(history.stream().mapToDouble(Double::doubleValue).average().orElse(0))
                .maxCpuUsage(history.stream().mapToDouble(Double::doubleValue).max().orElse(0))
                .minCpuUsage(history.stream().mapToDouble(Double::doubleValue).min().orElse(0))
                .p95CpuUsage(calculatePercentile(history, 95))
                .p99CpuUsage(calculatePercentile(history, 99))
                .samplesByThread(samplesByThreadMap)
                .hotMethods(hotMethods)
                .hotThreads(hotThreads)
                .build();
    }

    public Map<Long, StackTraceElement[]> getStackTraceSnapshots() {
        return new HashMap<>(stackTraceSnapshots);
    }

    private double calculatePercentile(List<Double> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    public boolean isRunning() {
        return running.get();
    }

    public Duration getElapsedTime() {
        return Duration.ofNanos(System.nanoTime() - startTimeNanos);
    }
}
