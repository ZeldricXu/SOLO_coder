package com.edgescheduler.modules.inference.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.edgescheduler.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inference_task")
public class InferenceTask extends BaseEntity {

    @TableField("task_id")
    private String taskId;

    @TableField("model_id")
    private String modelId;

    @TableField("model_name")
    private String modelName;

    @TableField("model_version")
    private String modelVersion;

    @TableField("device_id")
    private String deviceId;

    @TableField("task_status")
    private String taskStatus;

    @TableField("priority")
    private Integer priority;

    @TableField(value = "input_data", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> inputData;

    @TableField(value = "inference_result", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> inferenceResult;

    @TableField("inference_duration")
    private Long inferenceDuration;

    @TableField("scheduled_time")
    private LocalDateTime scheduledTime;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("completed_time")
    private LocalDateTime completedTime;

    @TableField("error_message")
    private String errorMessage;

    @TableField(value = "model_version_snapshot", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> modelVersionSnapshot;

    @TableField("rollback_available")
    private Boolean rollbackAvailable;

    @TableField("rollback_task_id")
    private String rollbackTaskId;

    @TableField("input_data_checksum")
    private String inputDataChecksum;
}
