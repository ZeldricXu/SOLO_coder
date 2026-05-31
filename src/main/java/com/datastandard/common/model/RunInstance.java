package com.datastandard.common.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "run_instances", autoResultMap = true)
public class RunInstance {

    @TableId(type = IdType.INPUT)
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

    @TableField("trace_id")
    private String traceId;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
