package com.modelguard.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GpuNodeResponse {

    private Long id;
    private String nodeId;
    private String hostname;
    private String ipAddress;
    private Integer gpuCount;
    private String gpuModel;
    private Integer totalGpuMemoryGb;
    private Integer availableGpuMemoryGb;
    private String status;
    private Map<String, Object> labels;
    private LocalDateTime lastHeartbeat;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
