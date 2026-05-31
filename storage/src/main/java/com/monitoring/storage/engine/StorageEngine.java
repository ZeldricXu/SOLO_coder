package com.monitoring.storage.engine;

import com.monitoring.storage.model.TimeSeriesPoint;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface StorageEngine {

    String getName();

    Mono<Void> write(TimeSeriesPoint point);

    Mono<Void> writeBatch(List<TimeSeriesPoint> points);

    Flux<TimeSeriesPoint> read(String metric, Instant startTime, Instant endTime, Map<String, String> tags);

    Mono<Map<String, Object>> getStats();

    Mono<Void> compact();

    Mono<Void> purge(Instant before);
}
