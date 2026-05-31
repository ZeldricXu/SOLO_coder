package com.datastandard.modules.metrics;

import com.datastandard.modules.metrics.dto.AggregateQuery;
import com.datastandard.modules.metrics.dto.MetricResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStorageAdapter implements StorageEngineAdapter {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String METRIC_KEY_PREFIX = "metric:";
    private static final String METRIC_SET_KEY = "metric:names";
    private static final long HOT_DATA_TTL_SECONDS = 86400;

    private String buildKey(String metricName, long timestamp) {
        return METRIC_KEY_PREFIX + metricName + ":" + timestamp;
    }

    @Override
    public Mono<Boolean> write(String metricName, Double value, Map<String, String> dimensions, Instant timestamp) {
        String key = buildKey(metricName, timestamp.toEpochMilli());
        try {
            Map<String, Object> data = Map.of(
                    "metricName", metricName,
                    "value", value,
                    "dimensions", dimensions,
                    "timestamp", timestamp.toString()
            );
            String jsonValue = objectMapper.writeValueAsString(data);
            return redisTemplate.opsForValue()
                    .set(key, jsonValue, HOT_DATA_TTL_SECONDS, TimeUnit.SECONDS)
                    .then(redisTemplate.opsForSet().add(METRIC_SET_KEY, metricName))
                    .thenReturn(true)
                    .onErrorResume(e -> {
                        log.error("Failed to write metric to Redis: {}", key, e);
                        return Mono.just(false);
                    });
        } catch (Exception e) {
            log.error("Failed to serialize metric data", e);
            return Mono.just(false);
        }
    }

    @Override
    public Mono<Boolean> batchWrite(List<Map<String, Object>> metricsBatch) {
        List<Mono<Boolean>> operations = new ArrayList<>();
        for (Map<String, Object> metric : metricsBatch) {
            String metricName = (String) metric.get("metricName");
            Double value = (Double) metric.get("value");
            @SuppressWarnings("unchecked")
            Map<String, String> dimensions = (Map<String, String>) metric.get("dimensions");
            Instant timestamp = (Instant) metric.get("timestamp");
            operations.add(write(metricName, value, dimensions, timestamp));
        }
        return Flux.merge(operations)
                .all(result -> result)
                .onErrorResume(e -> {
                    log.error("Failed to batch write metrics to Redis", e);
                    return Mono.just(false);
                });
    }

    @Override
    public Flux<MetricResponse> query(AggregateQuery query) {
        String pattern = METRIC_KEY_PREFIX + query.getMetricName() + ":*";
        long startMs = query.getStartTime().toEpochMilli();
        long endMs = query.getEndTime().toEpochMilli();

        return redisTemplate.keys(pattern)
                .filter(key -> {
                    String[] parts = key.split(":");
                    if (parts.length >= 3) {
                        try {
                            long ts = Long.parseLong(parts[parts.length - 1]);
                            return ts >= startMs && ts <= endMs;
                        } catch (NumberFormatException e) {
                            return false;
                        }
                    }
                    return false;
                })
                .collectList()
                .flatMapMany(keys -> {
                    if (keys.isEmpty()) {
                        return Flux.empty();
                    }
                    return redisTemplate.opsForValue().multiGet(keys)
                            .flatMapMany(Flux::fromIterable);
                })
                .mapNotNull(json -> {
                    try {
                        Map<String, Object> data = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
                        @SuppressWarnings("unchecked")
                        Map<String, String> dimensions = (Map<String, String>) data.get("dimensions");
                        return MetricResponse.builder()
                                .metricName((String) data.get("metricName"))
                                .value(((Number) data.get("value")).doubleValue())
                                .dimensions(dimensions)
                                .timestamp(Instant.parse((String) data.get("timestamp")))
                                .aggregateLevel(AggregateQuery.AggregateLevel.RAW)
                                .build();
                    } catch (Exception e) {
                        log.error("Failed to parse metric from Redis", e);
                        return null;
                    }
                });
    }

    @Override
    public Mono<Long> count(String metricName, Instant startTime, Instant endTime) {
        String pattern = METRIC_KEY_PREFIX + metricName + ":*";
        long startMs = startTime.toEpochMilli();
        long endMs = endTime.toEpochMilli();

        return redisTemplate.keys(pattern)
                .filter(key -> {
                    String[] parts = key.split(":");
                    if (parts.length >= 3) {
                        try {
                            long ts = Long.parseLong(parts[parts.length - 1]);
                            return ts >= startMs && ts <= endMs;
                        } catch (NumberFormatException e) {
                            return false;
                        }
                    }
                    return false;
                })
                .count();
    }

    @Override
    public Mono<Boolean> deleteOldData(Instant cutoffTime, AggregateQuery.AggregateLevel level) {
        String pattern = METRIC_KEY_PREFIX + "*";
        long cutoffMs = cutoffTime.toEpochMilli();

        return redisTemplate.keys(pattern)
                .filter(key -> {
                    String[] parts = key.split(":");
                    if (parts.length >= 3) {
                        try {
                            long ts = Long.parseLong(parts[parts.length - 1]);
                            return ts < cutoffMs;
                        } catch (NumberFormatException e) {
                            return false;
                        }
                    }
                    return false;
                })
                .collectList()
                .flatMap(keys -> {
                    if (keys.isEmpty()) {
                        return Mono.just(true);
                    }
                    return redisTemplate.delete(keys)
                            .map(deleted -> {
                                log.info("Deleted {} old metric keys from Redis", deleted);
                                return true;
                            });
                })
                .onErrorResume(e -> {
                    log.error("Failed to delete old data from Redis", e);
                    return Mono.just(false);
                });
    }

    public Mono<Set<String>> getAllMetricNames() {
        return redisTemplate.opsForSet().members(METRIC_SET_KEY);
    }

    @Override
    public String getStorageName() {
        return "redis";
    }

    @Override
    public boolean isHotStorage() {
        return true;
    }
}
