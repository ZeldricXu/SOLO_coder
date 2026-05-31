package com.scheduler.anomaly.detection.adapter;

import com.scheduler.anomaly.detection.model.MetricSeries;
import com.scheduler.persistence.entity.MetricsSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class MetricsAdapter {

    public MetricSeries toMetricSeries(List<MetricsSnapshot> snapshots, String metricName) {
        List<Double> values = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();

        for (MetricsSnapshot snapshot : snapshots) {
            Map<String, Object> metrics = snapshot.getMetrics();
            Object valueObj = metrics.get(metricName);
            if (valueObj instanceof Number) {
                values.add(((Number) valueObj).doubleValue());
                timestamps.add(snapshot.getTimestamp().toEpochMilli());
            }
        }

        return MetricSeries.builder()
                .metricName(metricName)
                .values(values)
                .timestamps(timestamps)
                .build();
    }

    public Set<String> extractMetricNames(List<MetricsSnapshot> snapshots) {
        return snapshots.stream()
                .flatMap(s -> s.getMetrics().keySet().stream())
                .collect(Collectors.toSet());
    }

    public double extractMetricValue(MetricsSnapshot snapshot, String metricName) {
        Object valueObj = snapshot.getMetrics().get(metricName);
        if (valueObj instanceof Number) {
            return ((Number) valueObj).doubleValue();
        }
        return 0.0;
    }
}
