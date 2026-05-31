package com.datastandard.modules.profiling.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfilingReport {

    private String sessionId;

    private String sessionName;

    private String description;

    private Instant startTime;

    private Instant endTime;

    private Duration actualDuration;

    private String status;

    private String jvmPid;

    private String jvmVersion;

    private CpuReport cpuReport;

    private MemoryReport memoryReport;

    private FlameGraphReport flameGraphReport;

    private List<String> recommendations;

    private String createdBy;

    private Instant createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CpuReport {
        private double averageCpuUsage;

        private double maxCpuUsage;

        private double minCpuUsage;

        private double p95CpuUsage;

        private double p99CpuUsage;

        private long totalSamples;

        private Map<String, Long> samplesByThread;

        private List<HotMethod> hotMethods;

        private List<HotThread> hotThreads;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryReport {
        private double averageHeapUsage;

        private double maxHeapUsage;

        private double minHeapUsage;

        private long heapUsedAfterGc;

        private long totalAllocatedBytes;

        private long gcCount;

        private Duration gcTotalTime;

        private double gcThroughput;

        private List<MemoryPoolStats> memoryPools;

        private List<AllocationSite> topAllocationSites;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FlameGraphReport {
        private String filePath;

        private String svgContent;

        private String interactiveUrl;

        private int totalFrames;

        private int totalSamples;

        private String rootFrame;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HotMethod {
        private String className;

        private String methodName;

        private String signature;

        private long samples;

        private double percentage;

        private String packageName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HotThread {
        private String threadName;

        private long threadId;

        private String state;

        private long samples;

        private double percentage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryPoolStats {
        private String name;

        private String type;

        private long usedBytes;

        private long maxBytes;

        private double usagePercent;

        private long peakUsedBytes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllocationSite {
        private String className;

        private String methodName;

        private long allocatedBytes;

        private long allocationCount;

        private double percentage;
    }
}
