package com.scheduler.core.service;

import com.scheduler.persistence.entity.MetricsSnapshot;
import com.scheduler.persistence.mapper.MetricsSnapshotMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsService {

    private final MeterRegistry meterRegistry;
    private final MetricsSnapshotMapper snapshotMapper;

    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);

    public void recordRequest() {
        totalRequests.incrementAndGet();
    }

    public void recordError() {
        totalErrors.incrementAndGet();
    }

    public MetricsSnapshot createSnapshot(String namespace, Map<String, String> dimensions) {
        MetricsSnapshot snapshot = new MetricsSnapshot();
        snapshot.setSnapshotId("snap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        snapshot.setTimestamp(Instant.now());
        snapshot.setNamespace(namespace);
        snapshot.setDimensions(dimensions);
        snapshot.setSource("scheduler-core");

        double errorRate = totalRequests.get() > 0
                ? (double) totalErrors.get() / totalRequests.get()
                : 0;

        snapshot.setMetrics(Map.of(
                "throughput", totalRequests.getAndSet(0) / 60.0,
                "error_rate", errorRate,
                "latency_p99", meterRegistry.get("http.server.requests").timer().takeSnapshot().mean()
        ));

        snapshotMapper.insert(snapshot);
        log.debug("Created metrics snapshot: {}", snapshot.getSnapshotId());
        return snapshot;
    }

    @Scheduled(fixedDelay = 60000)
    public void collectMetrics() {
        createSnapshot("default", Map.of(
                "host", System.getenv().getOrDefault("HOSTNAME", "localhost"),
                "region", "cn-east"
        ));
    }

    public Map<String, Object> getCurrentMetrics() {
        return Map.of(
                "totalRequests", totalRequests.get(),
                "totalErrors", totalErrors.get(),
                "errorRate", totalRequests.get() > 0 ? (double) totalErrors.get() / totalRequests.get() : 0
        );
    }
}
