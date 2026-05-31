package com.logmanager.domain.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
public class ErrorBudget extends BaseEntity {
    private String budgetId;
    private String sloId;
    private Double totalBudget;
    private Double remainingBudget;
    private Double consumedBudget;
    private Double burnRate;
    private Instant windowStart;
    private Instant windowEnd;
    private String status;
}
