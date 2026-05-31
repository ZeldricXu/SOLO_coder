package com.device.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
public class OfflineDataRequest {
    @NotBlank(message = "deviceId不能为空")
    private String deviceId;

    @NotBlank(message = "dataType不能为空")
    private String dataType;

    @NotNull(message = "payload不能为空")
    private Map<String, Object> payload;

    @NotNull(message = "collectedAt不能为空")
    private Instant collectedAt;

    private String checksum;
}
