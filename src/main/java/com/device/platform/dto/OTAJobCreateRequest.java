package com.device.platform.dto;

import com.device.platform.common.OTARolloutStrategy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.Instant;
import java.util.List;

@Data
public class OTAJobCreateRequest {
    @NotBlank(message = "firmwareId不能为空")
    private String firmwareId;

    @NotNull(message = "rolloutStrategy不能为空")
    private OTARolloutStrategy rolloutStrategy;

    private Integer batchSize;
    private List<String> deviceIds;
    private boolean autoRollback;
    private Double failureThreshold;
    private Instant scheduledAt;
}
