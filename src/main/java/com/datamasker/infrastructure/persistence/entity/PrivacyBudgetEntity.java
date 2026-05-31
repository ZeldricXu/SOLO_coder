package com.datamasker.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dm_privacy_budget")
public class PrivacyBudgetEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String queryId;

    private Double epsilonConsumed;

    private Double deltaConsumed;

    private Double totalBudget;

    private Double remainingBudget;

    private LocalDateTime createdAt;
}
