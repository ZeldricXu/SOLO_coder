package com.solocoder.platform.monitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerformanceSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    private double cpuUsagePercent;
    private long usedMemoryMb;
    private long totalMemoryMb;
    private int activeThreads;
    private long gcCount;
    private double gcTimeMs;
    private LocalDateTime capturedAt;

    public double getMemoryUsagePercent() {
        return totalMemoryMb > 0 ? (double) usedMemoryMb / totalMemoryMb * 100 : 0;
    }
}
