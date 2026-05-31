package com.logmanager.service.metrics.exporter;

import com.logmanager.domain.model.TimeSeriesMetric;
import com.logmanager.service.metrics.MetricsExporter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class MicrometerExporter implements MetricsExporter {

    private final MeterRegistry meterRegistry;

    @Override
    public Mono<Void> export(TimeSeriesMetric metric) {
        String serviceName = metric.getLabels() != null ? metric.getLabels().get("service") : "unknown";
        meterRegistry.counter(metric.getMetricName(), "service", serviceName)
                .increment(metric.getValue());
        return Mono.empty();
    }
}
