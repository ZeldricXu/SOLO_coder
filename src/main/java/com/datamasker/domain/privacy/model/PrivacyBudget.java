package com.datamasker.domain.privacy.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dm_privacy_budget")
public class PrivacyBudget {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String queryId;

    private double epsilonConsumed;

    private double deltaConsumed;

    private double totalBudget;

    private double remainingBudget;

    private LocalDateTime createdAt;
}
