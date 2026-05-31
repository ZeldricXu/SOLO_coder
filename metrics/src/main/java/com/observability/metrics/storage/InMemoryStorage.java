package com.observability.metrics.storage;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.observability.metrics.model.MetricPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class InMemoryStorage implements MetricStorage {

    private final Cache<String, List<MetricPoint>> metricCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();

    @Override
    public String getType() {
        return "in_memory";
    }

    @Override
    public void store(MetricPoint point) {
        String key = buildKey(point.getName(), point.getLabels());
        metricCache.asMap().compute(key, (k, points) -> {
            if (points == null) {
                points = Collections.synchronizedList(new ArrayList<>());
            }
            points.add(point);
            if (points.size() > 10000) {
                points.remove(0);
            }
            return points;
        });
    }

    @Override
    public List<MetricPoint> query(String metricName, long startTime, long endTime, Map<String, String> labels) {
        String key = buildKey(metricName, labels);
        List<MetricPoint> points = metricCache.getIfPresent(key);
        if (points == null) {
            return Collections.emptyList();
        }
        return points;
    }

    private String buildKey(String metricName, Map<String, String> labels) {
        StringBuilder sb = new StringBuilder(metricName);
        if (labels != null && !labels.isEmpty()) {
            sb.append("{");
            labels.forEach((k, v) -> sb.append(k).append("=").append(v).append(","));
            sb.append("}");
        }
        return sb.toString();
    }
}
