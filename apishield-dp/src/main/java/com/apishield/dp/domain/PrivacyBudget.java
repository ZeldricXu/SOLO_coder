package com.apishield.dp.domain;

import com.apishield.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class PrivacyBudget extends BaseEntity {
    private String budgetId;
    private String userId;
    private String dataSource;
    private double totalEpsilon;
    private double totalDelta;
    private double consumedEpsilon;
    private double consumedDelta;
    private double remainingEpsilon;
    private double remainingDelta;
    private LocalDateTime resetTime;
    private String resetPeriod;
    private boolean autoReset;
    private String status;
}
