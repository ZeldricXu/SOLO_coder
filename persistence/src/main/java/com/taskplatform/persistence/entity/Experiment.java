package com.taskplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskplatform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("experiments")
public class Experiment extends BaseEntity {

    @TableField("experiment_id")
    private String experimentId;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("type")
    private String type;

    @TableField("status")
    private String status;

    @TableField("control_prompt_id")
    private String controlPromptId;

    @TableField("treatment_prompt_ids")
    private String treatmentPromptIds;

    @TableField("traffic_split")
    private String trafficSplit;

    @TableField("metrics")
    private String metrics;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    @TableField("created_by")
    private String createdBy;

    @TableField("result")
    private String result;
}
