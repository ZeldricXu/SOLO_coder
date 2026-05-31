package com.solocoder.platform.gpu.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GpuTask implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskId;
    private String taskName;
    private int requiredMemoryMb;
    private int requiredCudaCores;
    private int priority;
    private TaskStatus status;
    private String assignedGpuId;
    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Map<String, String> metadata;
    private String errorMessage;
    private boolean preemptible;

    public enum TaskStatus {
        QUEUED, RUNNING, COMPLETED, FAILED, PREEMPTED
    }
}
