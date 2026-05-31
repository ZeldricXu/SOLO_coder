package com.modelguard.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GpuTaskResponse {

    private Long id;
    private String taskId;
    private String name;
    private String taskType;
    private Integer priority;
    private Integer requiredGpuCount;
    private Integer requiredGpuMemoryGb;
    private Long estimatedRuntimeMs;
    private String status;
    private String nodeId;
    private String gpuIndices;
    private BigDecimal progress;
    private Boolean preemptible;
    private Map<String, Object> payload;
    private String submittedBy;
    private Map<String, Object> labels;
    private String errorDetail;
    private LocalDateTime submittedAt;
    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
