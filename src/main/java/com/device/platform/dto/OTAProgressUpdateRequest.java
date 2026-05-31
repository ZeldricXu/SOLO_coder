package com.device.platform.dto;

import com.device.platform.common.OTAStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OTAProgressUpdateRequest {
    @NotBlank(message = "jobId不能为空")
    private String jobId;

    @NotBlank(message = "deviceId不能为空")
    private String deviceId;

    @NotNull(message = "status不能为空")
    private OTAStatus status;

    private Double progress;
    private String errorDetail;
}
