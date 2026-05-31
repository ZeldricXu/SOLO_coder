package com.edgescheduler.ruleengine.entity;

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
@TableName(value = "rule_execution", autoResultMap = true)
public class RuleExecution extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String runId;

    private String ruleId;

    private String deviceKey;

    private String phase;

    private BigDecimal progress;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> triggerData;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> resultData;

    private String errorDetail;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    public interface Phase {
        String PENDING = "pending";
        String EXECUTING = "executing";
        String COMPLETED = "completed";
        String FAILED = "failed";
    }
}
