package com.ratelimiter.service.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AggregatedStatsData implements Serializable {
    
    private String statId;
    private Map<AggregationDimension, String> dimensionValues;
    private long totalRequests;
    private long passedRequests;
    private long rejectedRequests;
    private long errorRequests;
    private long totalLatencyMs;
    private long startTime;
    private long endTime;
    private String period;
    
    public AggregatedStatsData(Map<AggregationDimension, String> dimensionValues) {
        this.dimensionValues = dimensionValues != null ? dimensionValues : new HashMap<>();
        this.totalRequests = 0;
        this.passedRequests = 0;
        this.rejectedRequests = 0;
        this.errorRequests = 0;
        this.totalLatencyMs = 0;
    }
    
    public void recordRequest(boolean passed, boolean isError, long latencyMs) {
        totalRequests++;
        if (passed) {
            passedRequests++;
        } else {
            rejectedRequests++;
        }
        if (isError) {
            errorRequests++;
        }
        totalLatencyMs += latencyMs;
    }
    
    public double getErrorRate() {
        if (totalRequests == 0) {
            return 0.0;
        }
        return (double) errorRequests / totalRequests;
    }
    
    public long getAvgLatencyMs() {
        if (totalRequests == 0) {
            return 0;
        }
        return totalLatencyMs / totalRequests;
    }
    
    public String getDimensionValue(AggregationDimension dimension) {
        return dimensionValues.get(dimension);
    }
    
    public String getDimensionKey() {
        StringBuilder key = new StringBuilder();
        for (Map.Entry<AggregationDimension, String> entry : dimensionValues.entrySet()) {
            if (key.length() > 0) {
                key.append(":");
            }
            key.append(entry.getKey().getCode()).append("=").append(entry.getValue());
        }
        return key.toString();
    }
}