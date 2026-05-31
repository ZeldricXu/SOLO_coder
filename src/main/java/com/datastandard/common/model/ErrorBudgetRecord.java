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
@TableName(value = "error_budget_records", autoResultMap = true)
public class ErrorBudgetRecord {

    @TableId(type = IdType.INPUT)
    @TableField("record_id")
    private String recordId;

    @TableField("slo_id")
    private String sloId;

    @TableField("window_start")
    private LocalDateTime windowStart;

    @TableField("window_end")
    private LocalDateTime windowEnd;

    @TableField("total_budget")
    private BigDecimal totalBudget;

    @TableField("consumed_budget")
    private BigDecimal consumedBudget;

    @TableField("remaining_budget")
    private BigDecimal remainingBudget;

    @TableField("burn_rate")
    private BigDecimal burnRate;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
