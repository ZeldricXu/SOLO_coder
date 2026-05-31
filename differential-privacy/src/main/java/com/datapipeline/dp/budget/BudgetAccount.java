package com.datapipeline.dp.budget;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BudgetAccount {

    private String accountId;
    private double totalEpsilon;
    private double remainingEpsilon;
    private double totalDelta;
    private double remainingDelta;
    private Instant createdAt;
    private Instant lastResetAt;

    @Builder.Default
    private List<BudgetUsage> usageHistory = new ArrayList<>();

    public double getEpsilonUsageRatio() {
        if (totalEpsilon == 0) return 0.0;
        return 1.0 - (remainingEpsilon / totalEpsilon);
    }

}
