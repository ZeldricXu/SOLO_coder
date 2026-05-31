package com.edgescheduler.modules.aggregation.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.edgescheduler.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("data_aggregation")
public class DataAggregation extends BaseEntity {

    @TableField("aggregation_id")
    private String aggregationId;

    @TableField("device_id")
    private String deviceId;

    @TableField("aggregation_type")
    private String aggregationType;

    @TableField("time_window")
    private String timeWindow;

    @TableField("data_points_count")
    private Integer dataPointsCount;

    @TableField(value = "aggregated_data", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> aggregatedData;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    @TableField("upload_status")
    private String uploadStatus;

    @TableField("upload_time")
    private LocalDateTime uploadTime;

    @TableField("checkpoint_id")
    private String checkpointId;

    @TableField("recovery_status")
    private String recoveryStatus;

    @TableField("failure_count")
    private Integer failureCount;

    @TableField("last_failure_time")
    private LocalDateTime lastFailureTime;

    @TableField("last_failure_reason")
    private String lastFailureReason;

    @TableField("data_checksum")
    private String dataChecksum;

    @TableField("recovered_from")
    private String recoveredFrom;
}
