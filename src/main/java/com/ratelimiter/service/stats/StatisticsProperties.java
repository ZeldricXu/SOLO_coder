package com.ratelimiter.service.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "ratelimiter.stats")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatisticsProperties {
    
    private boolean enabled = true;
    
    private long persistIntervalMs = 60000;
    
    private double highErrorRateThreshold = 0.1;
    
    private List<String> aggregationDimensions = defaultDimensions();
    
    private int maxStoredPeriods = 10080;
    
    private boolean enableAlerting = true;
    
    private List<String> alertChannels = new ArrayList<>();
    
    private static List<String> defaultDimensions() {
        List<String> defaults = new ArrayList<>();
        defaults.add("api_path");
        defaults.add("time_period");
        return defaults;
    }
    
    public List<AggregationDimension> getEnabledDimensions() {
        return AggregationDimension.fromCodes(aggregationDimensions);
    }
    
    public boolean isDimensionEnabled(AggregationDimension dimension) {
        return aggregationDimensions != null && 
               aggregationDimensions.stream()
                   .anyMatch(d -> d.equalsIgnoreCase(dimension.getCode()));
    }
    
    public void enableDimension(AggregationDimension dimension) {
        if (aggregationDimensions == null) {
            aggregationDimensions = new ArrayList<>();
        }
        String code = dimension.getCode();
        if (!aggregationDimensions.stream().anyMatch(d -> d.equalsIgnoreCase(code))) {
            aggregationDimensions.add(code);
        }
    }
    
    public void disableDimension(AggregationDimension dimension) {
        if (aggregationDimensions != null) {
            String code = dimension.getCode();
            aggregationDimensions.removeIf(d -> d.equalsIgnoreCase(code));
        }
    }
}