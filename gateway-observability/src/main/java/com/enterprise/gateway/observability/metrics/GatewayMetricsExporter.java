package com.enterprise.gateway.observability.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class GatewayMetricsExporter {

    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicInteger> activeConnections = new ConcurrentHashMap<>();

    public GatewayMetricsExporter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordRequestMetrics(String routeId, long durationMs, int statusCode, long requestSize, long responseSize, boolean error) {
        getActiveConnectionsGauge(routeId);

        DistributionSummary.builder("gateway.requests.size")
                .tag("routeId", routeId)
                .tag("type", "request")
                .register(meterRegistry)
                .record(requestSize);

        DistributionSummary.builder("gateway.requests.size")
                .tag("routeId", routeId)
                .tag("type", "response")
                .register(meterRegistry)
                .record(responseSize);

        DistributionSummary.builder("gateway.requests.duration")
                .tag("routeId", routeId)
                .tag("status", String.valueOf(statusCode))
                .tag("error", String.valueOf(error))
                .register(meterRegistry)
                .record(durationMs);
    }

    public void incrementActiveConnections(String routeId) {
        activeConnections.computeIfAbsent(routeId, k -> {
            AtomicInteger count = new AtomicInteger(0);
            Gauge.builder("gateway.connections.active", count, AtomicInteger::get)
                    .tag("routeId", routeId)
                    .register(meterRegistry);
            return count;
        }).incrementAndGet();
    }

    public void decrementActiveConnections(String routeId) {
        AtomicInteger count = activeConnections.get(routeId);
        if (count != null) {
            count.decrementAndGet();
        }
    }

    private AtomicInteger getActiveConnectionsGauge(String routeId) {
        return activeConnections.computeIfAbsent(routeId, k -> {
            AtomicInteger count = new AtomicInteger(0);
            Gauge.builder("gateway.connections.active", count, AtomicInteger::get)
                    .tag("routeId", routeId)
                    .register(meterRegistry);
            return count;
        });
    }
}
