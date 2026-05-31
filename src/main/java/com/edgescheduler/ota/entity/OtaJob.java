package com.edgescheduler.ota.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.edgescheduler.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ota_job", autoResultMap = true)
public class OtaJob extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String jobId;

    private String jobName;

    private String firmwareId;

    private String productKey;

    private String targetVersion;

    private String upgradeType;

    private String grayScaleStrategy;

    @TableField(typeHandler = JacksonTypeHandler.class)
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

    public interface Status {
        String PENDING = "pending";
        String RUNNING = "running";
        String SUCCESS = "success";
        String FAILED = "failed";
        String ROLLEDBACK = "rolledback";
        String PAUSED = "paused";
    }

    public interface GrayScaleStrategy {
        String BATCH = "batch";
        String PERCENTAGE = "percentage";
        String DEVICE_LIST = "device_list";
    }

    public interface UpgradeType {
        String FULL = "full";
        String DIFF = "diff";
    }
}
