package com.edgescheduler.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.edgescheduler.domain.enums.RunPhase;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("run_instance")
public class RunInstanceEntity extends BaseEntity {

    @TableField("run_id")
    private String runId;

    @TableField("entity_id")
    private String entityId;

    @TableField("phase")
    private RunPhase phase;

    @TableField("progress")
    private Double progress;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("error_detail")
    private String errorDetail;
}
