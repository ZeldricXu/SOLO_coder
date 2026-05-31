package com.edgescheduler.ota.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class OtaJobDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String jobId;

    @NotEmpty(message = "jobName cannot be empty")
    private String jobName;

    @NotEmpty(message = "firmwareId cannot be empty")
    private String firmwareId;

    @NotEmpty(message = "productKey cannot be empty")
    private String productKey;

    private String targetVersion;
    private String upgradeType;
    private String grayScaleStrategy;
    private Map<String, Object> grayScaleConfig;
    private Integer currentBatch;
    private Integer totalBatches;
    private String status;
    private String rollbackFirmwareId;
    private Integer autoRollbackEnabled;
    private BigDecimal successRateThreshold;
    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private List<String> deviceKeys;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
