package com.datastandard.modules.metrics;

import com.datastandard.modules.metrics.dto.AggregateQuery;
import com.datastandard.modules.metrics.dto.MetricResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface StorageEngineAdapter {

    Mono<Boolean> write(String metricName, Double value, Map<String, String> dimensions, Instant timestamp);

    Mono<Boolean> batchWrite(List<Map<String, Object>> metricsBatch);

    Flux<MetricResponse> query(AggregateQuery query);

    Mono<Long> count(String metricName, Instant startTime, Instant endTime);

    Mono<Boolean> deleteOldData(Instant cutoffTime, AggregateQuery.AggregateLevel level);

    String getStorageName();

    boolean isHotStorage();
}
