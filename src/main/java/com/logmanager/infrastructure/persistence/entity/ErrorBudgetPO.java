package com.logmanager.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
@TableName(value = "error_budget", autoResultMap = true)
public class ErrorBudgetPO {
    @TableId
    private String id;

    private String budgetId;

    private String sloId;

    private Double totalBudget;

    private Double remainingBudget;

    private Double consumedBudget;

    private Double burnRate;

    private Instant windowStart;

    private Instant windowEnd;

    private String status;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> attributes;

    private Instant createdAt;

    private Instant updatedAt;
}
