package com.taskflow.tenant.model;

import lombok.Data;

@Data
public class TenantQuota {
    private String tenantId;
    private String resourceType;
    private long maxLimit;
    private long currentUsage;
    private String unit;

    public boolean isExceeded() {
        return currentUsage >= maxLimit;
    }

    public double getUsagePercentage() {
        if (maxLimit == 0) return 100.0;
        return (double) currentUsage / maxLimit * 100;
    }
}
