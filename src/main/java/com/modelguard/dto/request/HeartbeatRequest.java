package com.modelguard.dto.request;

import lombok.Data;

@Data
public class HeartbeatRequest {

    private String nodeId;

    private Integer availableGpuMemoryGb;
}
