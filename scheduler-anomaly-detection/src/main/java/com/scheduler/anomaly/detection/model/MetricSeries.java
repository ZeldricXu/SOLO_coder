package com.scheduler.anomaly.detection.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricSeries {
    private String metricName;
    private List<Double> values;
    private List<Long> timestamps;

    public double[] toDoubleArray() {
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public int size() {
        return values != null ? values.size() : 0;
    }
}
