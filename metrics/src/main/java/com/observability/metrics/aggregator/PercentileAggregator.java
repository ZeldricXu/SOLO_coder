package com.observability.metrics.aggregator;

import com.observability.metrics.model.MetricPoint;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PercentileAggregator implements MetricAggregator {

    @Override
    public String getName() {
        return "percentile";
    }

    @Override
    public Map<String, Object> aggregate(List<MetricPoint> points) {
        Map<String, Object> result = new HashMap<>();
        if (points == null || points.isEmpty()) {
            result.put("p50", 0.0);
            result.put("p95", 0.0);
            result.put("p99", 0.0);
            return result;
        }

        List<Double> sorted = points.stream()
                .map(MetricPoint::getValue)
                .sorted()
                .toList();

        result.put("p50", calculatePercentile(sorted, 50));
        result.put("p95", calculatePercentile(sorted, 95));
        result.put("p99", calculatePercentile(sorted, 99));

        return result;
    }

    private double calculatePercentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }
}
