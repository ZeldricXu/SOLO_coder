package com.datastandard.modules.profiling;

import com.datastandard.modules.profiling.dto.ProfilingReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class MemorySampler {

    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    private final List<MemoryPoolMXBean> memoryPoolMXBeans = ManagementFactory.getMemoryPoolMXBeans();
    private final List<GarbageCollectorMXBean> gcMXBeans = ManagementFactory.getGarbageCollectorMXBeans();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<MemorySnapshot> snapshots = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, AtomicLong> allocationSites = new ConcurrentHashMap<>();
    private final AtomicLong totalAllocatedBytes = new AtomicLong(0);

    private Thread samplerThread;
    private int samplingIntervalMs = 100;
    private long startTimeNanos;
    private long initialGcCount = 0;
    private long initialGcTime = 0;
    private long initialHeapUsage = 0;

    public synchronized void start(int intervalMs) {
        if (running.compareAndSet(false, true)) {
            this.samplingIntervalMs = intervalMs;
            this.snapshots.clear();
            this.allocationSites.clear();
            this.totalAllocatedBytes.set(0);
            this.startTimeNanos = System.nanoTime();

            for (GarbageCollectorMXBean gcBean : gcMXBeans) {
                initialGcCount += gcBean.getCollectionCount();
                initialGcTime += gcBean.getCollectionTime();
            }
            initialHeapUsage = memoryMXBean.getHeapMemoryUsage().getUsed();

            samplerThread = new Thread(this::samplingLoop, "memory-sampler");
            samplerThread.setDaemon(true);
            samplerThread.start();

            log.info("Memory Sampler started with interval {}ms", intervalMs);
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
            log.info("Memory Sampler stopped. Snapshots: {}", snapshots.size());
        }
    }

    private void samplingLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                sampleMemory();
                Thread.sleep(samplingIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in memory sampling", e);
            }
        }
    }

    private void sampleMemory() {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();

        Map<String, MemoryUsage> poolUsages = new HashMap<>();
        for (MemoryPoolMXBean poolBean : memoryPoolMXBeans) {
            poolUsages.put(poolBean.getName(), poolBean.getUsage());
        }

        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean gcBean : gcMXBeans) {
            gcCount += gcBean.getCollectionCount();
            gcTime += gcBean.getCollectionTime();
        }

        MemorySnapshot snapshot = MemorySnapshot.builder()
                .timestamp(Instant.now())
                .heapUsed(heapUsage.getUsed())
                .heapMax(heapUsage.getMax())
                .nonHeapUsed(nonHeapUsage.getUsed())
                .nonHeapMax(nonHeapUsage.getMax())
                .poolUsages(poolUsages)
                .gcCount(gcCount - initialGcCount)
                .gcTime(gcTime - initialGcTime)
                .build();

        snapshots.add(snapshot);

        if (snapshots.size() > 10000) {
            snapshots.removeFirst();
        }

        if (snapshot.heapUsed < initialHeapUsage) {
            initialHeapUsage = snapshot.heapUsed;
        }
        totalAllocatedBytes.set(snapshot.heapUsed - initialHeapUsage +
                (gcCount - initialGcCount) * heapUsage.getMax() / 10);
    }

    public ProfilingReport.MemoryReport buildReport() {
        List<MemorySnapshot> snapList = new ArrayList<>(snapshots);

        List<Double> heapUsagePercentages = snapList.stream()
                .map(s -> s.heapMax > 0 ? (s.heapUsed * 100.0 / s.heapMax) : 0)
                .toList();

        MemorySnapshot lastSnapshot = snapList.isEmpty() ? null : snapList.get(snapList.size() - 1);

        List<ProfilingReport.MemoryPoolStats> poolStats = new ArrayList<>();
        if (lastSnapshot != null) {
            for (Map.Entry<String, MemoryUsage> entry : lastSnapshot.poolUsages.entrySet()) {
                MemoryUsage usage = entry.getValue();
                poolStats.add(ProfilingReport.MemoryPoolStats.builder()
                        .name(entry.getKey())
                        .type(detectPoolType(entry.getKey()))
                        .usedBytes(usage.getUsed())
                        .maxBytes(usage.getMax())
                        .usagePercent(usage.getMax() > 0 ? (usage.getUsed() * 100.0 / usage.getMax()) : 0)
                        .peakUsedBytes(usage.getUsed())
                        .build());
            }
        }

        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean gcBean : gcMXBeans) {
            gcCount += gcBean.getCollectionCount();
            gcTime += gcBean.getCollectionTime();
        }
        long actualGcCount = gcCount - initialGcCount;
        long actualGcTime = gcTime - initialGcTime;

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startTimeNanos);
        double gcThroughput = elapsed.toMillis() > 0 ?
                100.0 - (actualGcTime * 100.0 / elapsed.toMillis()) : 100;

        List<ProfilingReport.AllocationSite> topAllocations = allocationSites.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(10)
                .map(entry -> {
                    String[] parts = entry.getKey().split("\\.", 2);
                    String className = parts[0];
                    String methodName = parts.length > 1 ? parts[1] : "";
                    long bytes = entry.getValue().get();
                    return ProfilingReport.AllocationSite.builder()
                            .className(className)
                            .methodName(methodName)
                            .allocatedBytes(bytes)
                            .allocationCount(bytes / 1024)
                            .percentage(totalAllocatedBytes.get() > 0 ?
                                    (bytes * 100.0 / totalAllocatedBytes.get()) : 0)
                            .build();
                })
                .toList();

        return ProfilingReport.MemoryReport.builder()
                .averageHeapUsage(heapUsagePercentages.stream().mapToDouble(Double::doubleValue).average().orElse(0))
                .maxHeapUsage(heapUsagePercentages.stream().mapToDouble(Double::doubleValue).max().orElse(0))
                .minHeapUsage(heapUsagePercentages.stream().mapToDouble(Double::doubleValue).min().orElse(0))
                .heapUsedAfterGc(lastSnapshot != null ? lastSnapshot.heapUsed : 0)
                .totalAllocatedBytes(totalAllocatedBytes.get())
                .gcCount(actualGcCount)
                .gcTotalTime(Duration.ofMillis(actualGcTime))
                .gcThroughput(gcThroughput)
                .memoryPools(poolStats)
                .topAllocationSites(topAllocations)
                .build();
    }

    private String detectPoolType(String poolName) {
        if (poolName.contains("Eden")) return "EDEN";
        if (poolName.contains("Survivor")) return "SURVIVOR";
        if (poolName.contains("Old") || poolName.contains("Tenured")) return "OLD_GEN";
        if (poolName.contains("Perm") || poolName.contains("Metaspace")) return "METASPACE";
        if (poolName.contains("Code")) return "CODE_CACHE";
        return "OTHER";
    }

    public boolean isRunning() {
        return running.get();
    }

    public Duration getElapsedTime() {
        return Duration.ofNanos(System.nanoTime() - startTimeNanos);
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MemorySnapshot {
        private Instant timestamp;
        private long heapUsed;
        private long heapMax;
        private long nonHeapUsed;
        private long nonHeapMax;
        private Map<String, MemoryUsage> poolUsages;
        private long gcCount;
        private long gcTime;
    }
}
