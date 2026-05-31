package com.logmanager.service.metrics;

import com.logmanager.domain.model.TimeSeriesMetric;
import reactor.core.publisher.Mono;

public interface MetricsExporter {
    Mono<Void> export(TimeSeriesMetric metric);
}
