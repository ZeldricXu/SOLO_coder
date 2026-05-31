package com.contractai.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("run_instance")
public class RunInstance extends TenantBaseEntity {

    @TableField("run_id")
    private String runId;

    @TableField("entity_id")
    private String entityId;

    @TableField("entity_type")
    private String entityType;

    @TableField("phase")
    private String phase;

    @TableField("progress")
    private BigDecimal progress;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("status")
    private String status;

    @TableField("error_detail")
    private String errorDetail;

    @TableField(value = "attributes", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> attributes;
}
