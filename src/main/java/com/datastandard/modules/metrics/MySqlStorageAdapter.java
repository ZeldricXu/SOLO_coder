package com.datastandard.modules.metrics;

import com.datastandard.modules.metrics.dto.AggregateQuery;
import com.datastandard.modules.metrics.dto.MetricResponse;
import com.datastandard.modules.metrics.entity.MetricSnapshot;
import com.datastandard.modules.metrics.mapper.MetricSnapshotMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MySqlStorageAdapter implements StorageEngineAdapter {

    private final MetricSnapshotMapper metricSnapshotMapper;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Boolean> write(String metricName, Double value, Map<String, String> dimensions, Instant timestamp) {
        return Mono.fromCallable(() -> {
            try {
                MetricSnapshot snapshot = MetricSnapshot.builder()
                        .timestamp(timestamp)
                        .metrics(objectMapper.writeValueAsString(Map.of(
                                "metricName", metricName,
                                "value", value
                        )))
                        .dimensions(objectMapper.writeValueAsString(dimensions))
                        .aggregateLevel(AggregateQuery.AggregateLevel.RAW.name().toLowerCase())
                        .createdAt(Instant.now())
                        .build();
                int result = metricSnapshotMapper.insert(snapshot);
                return result > 0;
            } catch (Exception e) {
                log.error("Failed to write metric to MySQL", e);
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Boolean> batchWrite(List<Map<String, Object>> metricsBatch) {
        return Mono.fromCallable(() -> {
            try {
                List<MetricSnapshot> snapshots = new ArrayList<>();
                for (Map<String, Object> metric : metricsBatch) {
                    MetricSnapshot snapshot = MetricSnapshot.builder()
                            .timestamp((Instant) metric.get("timestamp"))
                            .metrics(objectMapper.writeValueAsString(Map.of(
                                    "metricName", metric.get("metricName"),
                                    "value", metric.get("value")
                            )))
                            .dimensions(objectMapper.writeValueAsString(metric.get("dimensions")))
                            .aggregateLevel(AggregateQuery.AggregateLevel.RAW.name().toLowerCase())
                            .createdAt(Instant.now())
                            .build();
                    snapshots.add(snapshot);
                }
                snapshots.forEach(metricSnapshotMapper::insert);
                return true;
            } catch (Exception e) {
                log.error("Failed to batch write metrics to MySQL", e);
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<MetricResponse> query(AggregateQuery query) {
        return Mono.fromCallable(() -> {
            List<MetricSnapshot> snapshots = metricSnapshotMapper.findByMetricNameAndTimeRange(
                    query.getMetricName(),
                    query.getStartTime(),
                    query.getEndTime(),
                    query.getAggregateLevel() != null ? query.getAggregateLevel().name().toLowerCase() : "raw"
            );
            return snapshots;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(Flux::fromIterable)
        .mapNotNull(snapshot -> {
            try {
                Map<String, Object> metricsData = objectMapper.readValue(
                        snapshot.getMetrics(), new TypeReference<Map<String, Object>>() {});
                Map<String, String> dimensionsData = objectMapper.readValue(
                        snapshot.getDimensions(), new TypeReference<Map<String, String>>() {});
                return MetricResponse.builder()
                        .metricName((String) metricsData.get("metricName"))
                        .value(((Number) metricsData.get("value")).doubleValue())
                        .dimensions(dimensionsData)
                        .timestamp(snapshot.getTimestamp())
                        .aggregateLevel(AggregateQuery.AggregateLevel.valueOf(snapshot.getAggregateLevel().toUpperCase()))
                        .build();
            } catch (Exception e) {
                log.error("Failed to parse metric snapshot", e);
                return null;
            }
        });
    }

    @Override
    public Mono<Long> count(String metricName, Instant startTime, Instant endTime) {
        return Mono.fromCallable(() -> metricSnapshotMapper.countByTimeRange(startTime, endTime))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Boolean> deleteOldData(Instant cutoffTime, AggregateQuery.AggregateLevel level) {
        return Mono.fromCallable(() -> {
            int deleted = metricSnapshotMapper.deleteOldSnapshots(cutoffTime, level.name().toLowerCase());
            log.info("Deleted {} old metric snapshots from MySQL, level: {}", deleted, level);
            return deleted >= 0;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public String getStorageName() {
        return "mysql";
    }

    @Override
    public boolean isHotStorage() {
        return false;
    }
}
