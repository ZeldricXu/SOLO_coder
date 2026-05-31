package com.device.platform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.device.platform.common.OTAStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ota_device")
public class OTADevice extends BaseEntity {
    private String jobId;
    private String deviceId;
    private Integer batchNumber;
    private OTAStatus upgradeStatus;
    private String currentVersion;
    private String targetVersion;
    private Double progress;
    private Instant startedAt;
    private Instant completedAt;
    private String errorDetail;
    private boolean rollbackRequired;
    private Instant rollbackStartedAt;
    private Instant rollbackCompletedAt;
    private String rollbackError;
}
