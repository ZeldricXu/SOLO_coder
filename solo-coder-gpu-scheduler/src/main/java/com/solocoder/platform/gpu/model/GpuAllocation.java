package com.solocoder.platform.gpu.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GpuAllocation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String allocationId;
    private String gpuId;
    private String taskId;
    private int allocatedMemoryMb;
    private int allocatedCudaCores;
    private AllocationStatus status;

    public enum AllocationStatus {
        ACTIVE, RELEASED, PREEMPTED
    }
}
