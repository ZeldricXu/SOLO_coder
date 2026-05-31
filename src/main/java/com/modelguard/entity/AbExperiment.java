package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ab_experiment", autoResultMap = true)
public class AbExperiment extends BaseEntity {

    @TableField("experiment_id")
    private String experimentId;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("control_group_prompt_id")
    private String controlGroupPromptId;

    @TableField("control_group_prompt_version")
    private Integer controlGroupPromptVersion;

    @TableField("experiment_group_prompt_id")
    private String experimentGroupPromptId;

    @TableField("experiment_group_prompt_version")
    private Integer experimentGroupPromptVersion;

    @TableField("traffic_split")
    private BigDecimal trafficSplit;

    @TableField("status")
    private String status;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("ended_at")
    private LocalDateTime endedAt;

    @TableField("created_by")
    private String createdBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
