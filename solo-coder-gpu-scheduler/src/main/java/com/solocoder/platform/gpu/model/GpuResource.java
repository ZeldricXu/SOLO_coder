package com.solocoder.platform.gpu.model;

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
public class GpuResource implements Serializable {

    private static final long serialVersionUID = 1L;

    private String gpuId;
    private String name;
    private int totalMemoryMb;
    private int usedMemoryMb;
    private int cudaCores;
    private double utilizationPercent;
    private GpuStatus status;
    private LocalDateTime lastHeartbeat;

    public enum GpuStatus {
        AVAILABLE, BUSY, OFFLINE, MAINTENANCE
    }

    public int getAvailableMemoryMb() {
        return totalMemoryMb - usedMemoryMb;
    }
}
