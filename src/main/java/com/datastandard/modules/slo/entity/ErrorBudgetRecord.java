package com.datastandard.modules.slo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("error_budget_records")
public class ErrorBudgetRecord {

    @TableId(type = IdType.ASSIGN_UUID)
    private String recordId;

    private String sloId;

    private Instant windowStart;

    private Instant windowEnd;

    private Double totalBudget;

    private Double consumedBudget;

    private Double remainingBudget;

    private Double burnRate;

    private Double currentSliValue;

    private String budgetStatus;

    private Instant estimatedExhaustionTime;

    private String metadata;

    private Instant createdAt;

    private Integer deleted;
}
