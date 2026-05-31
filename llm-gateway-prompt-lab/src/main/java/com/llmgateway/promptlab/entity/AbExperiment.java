package com.llmgateway.promptlab.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("ab_experiment")
public class AbExperiment implements Serializable {

    @TableId(value = "experiment_id", type = IdType.INPUT)
    private String experimentId;

    @TableField("experiment_name")
    private String experimentName;

    @TableField("description")
    private String description;

    @TableField("experiment_type")
    private String experimentType;

    @TableField("status")
    private String status;

    @TableField("traffic_percentage")
    private Integer trafficPercentage;

    @TableField(value = "variants", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> variants;

    @TableField(value = "metrics", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> metrics;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
