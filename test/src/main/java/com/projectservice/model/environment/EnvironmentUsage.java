package com.projectservice.model.environment;

import java.time.LocalDateTime;

public class EnvironmentUsage {
    private String id;
    private String environmentId;
    private String resourceType;
    private double usageValue;
    private LocalDateTime recordedAt;

    public EnvironmentUsage() {}

    public EnvironmentUsage(String id, String environmentId, String resourceType,
                           double usageValue, LocalDateTime recordedAt) {
        this.id = id;
        this.environmentId = environmentId;
        this.resourceType = resourceType;
        this.usageValue = usageValue;
        this.recordedAt = recordedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public double getUsageValue() { return usageValue; }
    public void setUsageValue(double usageValue) { this.usageValue = usageValue; }
    public LocalDateTime getRecordedAt() { return recordedAt; }
    public void setRecordedAt(LocalDateTime recordedAt) { this.recordedAt = recordedAt; }
}
