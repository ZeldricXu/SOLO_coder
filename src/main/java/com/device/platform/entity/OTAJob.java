package com.device.platform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.device.platform.common.OTARolloutStrategy;
import com.device.platform.common.OTAStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ota_job")
public class OTAJob extends BaseEntity {
    private String jobId;
    private String firmwareId;
    private String targetVersion;
    private OTARolloutStrategy rolloutStrategy;
    private Integer batchSize;
    private Integer currentBatch;
    private Integer totalBatches;
    private Integer successCount;
    private Integer failedCount;
    private Integer totalDevices;
    private boolean autoRollback;
    private Double failureThreshold;
    private Instant scheduledAt;
    private Instant startedAt;
    private Instant completedAt;
    private String status;
}
