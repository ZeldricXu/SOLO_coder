package com.enterprise.gateway.observability.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayMetricsExporterTest {

    private GatewayMetricsExporter exporter;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        exporter = new GatewayMetricsExporter(meterRegistry);
    }

    @Test
    void shouldRecordRequestMetrics() {
        exporter.recordRequestMetrics("test-route", 150, 200, 1024, 2048, false);

        assertThat(meterRegistry.find("gateway.requests.duration")
                .tag("routeId", "test-route")
                .tag("status", "200")
                .tag("error", "false")
                .summary()).isNotNull();

        assertThat(meterRegistry.find("gateway.connections.active")
                .tag("routeId", "test-route")
                .gauge()).isNotNull();
    }

    @Test
    void shouldIncrementActiveConnections() {
        exporter.incrementActiveConnections("test-route");
        exporter.incrementActiveConnections("test-route");
        exporter.incrementActiveConnections("test-route");

        double activeConnections = meterRegistry.get("gateway.connections.active")
                .tag("routeId", "test-route")
                .gauge()
                .value();

        assertThat(activeConnections).isEqualTo(3.0);

        exporter.decrementActiveConnections("test-route");

        activeConnections = meterRegistry.get("gateway.connections.active")
                .tag("routeId", "test-route")
                .gauge()
                .value();

        assertThat(activeConnections).isEqualTo(2.0);
    }

    @Test
    void shouldRecordRequestAndResponseSize() {
        exporter.recordRequestMetrics("test-route", 100, 200, 512, 1024, false);

        double requestSize = meterRegistry.get("gateway.requests.size")
                .tag("routeId", "test-route")
                .tag("type", "request")
                .summary()
                .totalAmount();

        double responseSize = meterRegistry.get("gateway.requests.size")
                .tag("routeId", "test-route")
                .tag("type", "response")
                .summary()
                .totalAmount();

        assertThat(requestSize).isEqualTo(512.0);
        assertThat(responseSize).isEqualTo(1024.0);
    }
}
