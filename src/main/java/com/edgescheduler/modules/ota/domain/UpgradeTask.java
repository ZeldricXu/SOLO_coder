package com.edgescheduler.modules.ota.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.edgescheduler.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("upgrade_task")
public class UpgradeTask extends BaseEntity {

    @TableField("task_id")
    private String taskId;

    @TableField("firmware_id")
    private String firmwareId;

    @TableField("device_id")
    private String deviceId;

    @TableField("upgrade_status")
    private String upgradeStatus;

    @TableField("upgrade_phase")
    private String upgradePhase;

    @TableField("progress")
    private Integer progress;

    @TableField("grayscale_group")
    private String grayscaleGroup;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("max_retries")
    private Integer maxRetries;

    @TableField("scheduled_time")
    private LocalDateTime scheduledTime;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("completed_time")
    private LocalDateTime completedTime;

    @TableField("error_message")
    private String errorMessage;

    @TableField("rollback_reason")
    private String rollbackReason;

    @TableField("rollback_time")
    private LocalDateTime rollbackTime;

    @TableField("original_version")
    private String originalVersion;

    @TableField("target_version")
    private String targetVersion;
}
