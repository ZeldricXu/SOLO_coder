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
@TableName(value = "run_instance", autoResultMap = true)
public class RunInstance extends BaseEntity {

    @TableField("run_id")
    private String runId;

    @TableField("entity_id")
    private String entityId;

    @TableField("phase")
    private String phase;

    @TableField("progress")
    private BigDecimal progress;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("error_detail")
    private String errorDetail;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
