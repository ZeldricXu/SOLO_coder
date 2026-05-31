package com.observability.metrics.aggregator;

import com.observability.metrics.model.MetricPoint;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class SummaryAggregator implements MetricAggregator {

    @Override
    public String getName() {
        return "summary";
    }

    @Override
    public Map<String, Object> aggregate(List<MetricPoint> points) {
        Map<String, Object> result = new HashMap<>();
        if (points == null || points.isEmpty()) {
            result.put("count", 0);
            result.put("sum", 0.0);
            result.put("avg", 0.0);
            result.put("min", 0.0);
            result.put("max", 0.0);
            return result;
        }

        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;

        for (MetricPoint point : points) {
            sum += point.getValue();
            min = Math.min(min, point.getValue());
            max = Math.max(max, point.getValue());
        }

        result.put("count", points.size());
        result.put("sum", sum);
        result.put("avg", sum / points.size());
        result.put("min", min);
        result.put("max", max);

        return result;
    }
}
