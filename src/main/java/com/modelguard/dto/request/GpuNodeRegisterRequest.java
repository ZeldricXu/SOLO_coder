package com.modelguard.dto.request;

import lombok.Data;
import java.util.Map;

@Data
public class GpuNodeRegisterRequest {

    private String hostname;

    private String ipAddress;

    private Integer gpuCount;

    private String gpuModel;

    private Integer totalGpuMemoryGb;

    private Map<String, Object> labels;
}
