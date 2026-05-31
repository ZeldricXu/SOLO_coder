package com.modelguard.dto.request;

import lombok.Data;
import java.util.Map;

@Data
public class GpuTaskSubmitRequest {

    private String name;

    private String taskType;

    private Integer priority;

    private Integer requiredGpuCount;

    private Integer requiredGpuMemoryGb;

    private Long estimatedRuntimeMs;

    private Boolean preemptible;

    private Map<String, Object> payload;

    private String submittedBy;

    private Map<String, Object> labels;
}
