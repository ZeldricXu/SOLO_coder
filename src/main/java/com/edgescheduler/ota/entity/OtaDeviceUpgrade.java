package com.edgescheduler.ota.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edgescheduler.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ota_device_upgrade", autoResultMap = true)
public class OtaDeviceUpgrade extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String jobId;

    private String deviceKey;

    private Integer batchNumber;

    private String currentVersion;

    private String targetVersion;

    private String status;

    private Integer progress;

    private String errorCode;

    private String errorMessage;

    private Integer retryCount;

    private LocalDateTime downloadStartedAt;

    private LocalDateTime upgradeStartedAt;

    private LocalDateTime completedAt;

    public interface Status {
        String PENDING = "pending";
        String DOWNLOADING = "downloading";
        String UPGRADING = "upgrading";
        String SUCCESS = "success";
        String FAILED = "failed";
        String ROLLBACK = "rollback";
        String ROLLBACK_SUCCESS = "rollback_success";
        String ROLLBACK_FAILED = "rollback_failed";
    }
}
