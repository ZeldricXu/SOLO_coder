package com.dynamiclog.offline.service;

import com.dynamiclog.common.entity.OfflineData;
import com.dynamiclog.common.enums.SyncStatus;
import com.dynamiclog.common.util.IdGenerator;
import com.dynamiclog.persistence.mapper.OfflineDataMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.micrometer.core.instrument.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfflineCacheService {

    private final OfflineDataMapper offlineDataMapper;
    private final WebClient.Builder webClientBuilder;
    private final MeterRegistry meterRegistry;

    private final Cache<String, OfflineData> localCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(10000)
            .recordStats()
            .build();

    private volatile boolean networkAvailable = true;
    private final Sinks.Many<SyncEvent> eventSink = Sinks.many().multicast().onBackpressureBuffer();
    private final Map<String, SyncMetrics> syncMetricsMap = new ConcurrentHashMap<>();

    private final Counter dataSavedCounter;
    private final Counter syncSuccessCounter;
    private final Counter syncFailedCounter;
    private final Counter syncRetriedCounter;
    private final Timer syncLatencyTimer;
    private final DistributionSummary dataSizeSummary;
    private final AtomicLong pendingSyncGauge;
    private final AtomicLong totalDataSizeGauge;

    public OfflineCacheService(
            OfflineDataMapper offlineDataMapper,
            WebClient.Builder webClientBuilder,
            MeterRegistry meterRegistry) {
        this.offlineDataMapper = offlineDataMapper;
        this.webClientBuilder = webClientBuilder;
        this.meterRegistry = meterRegistry;

        this.dataSavedCounter = Counter.builder("offline.data.saved")
                .description("Number of offline data records saved")
                .register(meterRegistry);
        this.syncSuccessCounter = Counter.builder("offline.sync.success")
                .description("Number of successful syncs")
                .register(meterRegistry);
        this.syncFailedCounter = Counter.builder("offline.sync.failed")
                .description("Number of failed syncs")
                .register(meterRegistry);
        this.syncRetriedCounter = Counter.builder("offline.sync.retried")
                .description("Number of sync retries")
                .register(meterRegistry);
        this.syncLatencyTimer = Timer.builder("offline.sync.latency")
                .description("Sync latency distribution")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.dataSizeSummary = DistributionSummary.builder("offline.data.size")
                .description("Offline data size distribution")
                .baseUnit("bytes")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.pendingSyncGauge = new AtomicLong(0);
        this.totalDataSizeGauge = new AtomicLong(0);

        Gauge.builder("offline.pending.sync", pendingSyncGauge, AtomicLong::get)
                .description("Number of pending sync items")
                .register(meterRegistry);
        Gauge.builder("offline.data.total.size", totalDataSizeGauge, AtomicLong::get)
                .description("Total size of offline data")
                .baseUnit("bytes")
                .register(meterRegistry);
        Gauge.builder("offline.network.available", () -> networkAvailable ? 1 : 0)
                .description("Network availability status")
                .register(meterRegistry);
    }

    public Mono<OfflineData> saveOfflineData(String dataType, String dataKey, String payload, String sourceDevice) {
        return Mono.fromCallable(() -> {
            String checksum = calculateChecksum(payload);
            long sizeBytes = (long) payload.getBytes().length;

            OfflineData data = new OfflineData();
            data.setId(IdGenerator.generateId("off"));
            data.setDataType(dataType);
            data.setDataKey(dataKey);
            data.setPayload(payload);
            data.setChecksum(checksum);
            data.setSyncStatus(SyncStatus.PENDING);
            data.setSourceDevice(sourceDevice);
            data.setSizeBytes(sizeBytes);

            offlineDataMapper.insert(data);
            localCache.put(dataKey, data);

            dataSavedCounter.increment();
            dataSizeSummary.record(sizeBytes);
            pendingSyncGauge.incrementAndGet();
            totalDataSizeGauge.addAndGet(sizeBytes);

            SyncMetrics metrics = syncMetricsMap.computeIfAbsent(dataType, k -> new SyncMetrics());
            metrics.recordSaved(sizeBytes);

            emitEvent(new SyncEvent("data.saved", dataKey, dataType, sizeBytes, SyncStatus.PENDING));

            if (networkAvailable) {
                syncToCloud(data).subscribeOn(Schedulers.boundedElastic()).subscribe();
            }

            log.info("Offline data saved: key={}, type={}, size={}", dataKey, dataType, data.getSizeBytes());
            return data;
        });
    }

    public Mono<OfflineData> getOfflineData(String dataKey) {
        return Mono.fromCallable(() -> {
            OfflineData cached = localCache.getIfPresent(dataKey);
            if (cached != null) {
                return cached;
            }
            OfflineData data = offlineDataMapper.findLatestByDataKey(dataKey);
            if (data != null) {
                localCache.put(dataKey, data);
            }
            return data;
        });
    }

    public Flux<OfflineData> getPendingSyncData() {
        return Mono.fromCallable(() -> offlineDataMapper.findBySyncStatus(SyncStatus.PENDING))
                .flatMapMany(Flux::fromIterable);
    }

    public Mono<Void> syncAllPending() {
        return getPendingSyncData()
                .flatMap(this::syncToCloud, 5)
                .then();
    }

    public Mono<OfflineData> syncToCloud(OfflineData data) {
        return Mono.fromCallable(() -> {
            data.setSyncStatus(SyncStatus.SYNCING);
            offlineDataMapper.updateById(data);
            emitEvent(new SyncEvent("sync.started", data.getDataKey(), data.getDataType(), data.getSizeBytes(), SyncStatus.SYNCING));
            return data;
        }).flatMap(d -> {
            if (!networkAvailable) {
                return Mono.just(d);
            }

            Timer.Sample sample = Timer.start(meterRegistry);
            long startTime = System.currentTimeMillis();

            return webClientBuilder.build()
                    .post()
                    .uri("/api/v1/cloud/sync")
                    .bodyValue(d)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .then(Mono.fromCallable(() -> {
                        sample.stop(syncLatencyTimer);
                        long latency = System.currentTimeMillis() - startTime;

                        d.setSyncStatus(SyncStatus.SYNCED);
                        d.setSyncedAt(LocalDateTime.now());
                        d.setRetryCount(0);
                        offlineDataMapper.updateById(d);

                        syncSuccessCounter.increment();
                        pendingSyncGauge.decrementAndGet();
                        totalDataSizeGauge.addAndGet(-d.getSizeBytes());

                        SyncMetrics metrics = syncMetricsMap.get(d.getDataType());
                        if (metrics != null) {
                            metrics.recordSuccess(latency);
                        }

                        emitEvent(new SyncEvent("sync.success", d.getDataKey(), d.getDataType(), d.getSizeBytes(), SyncStatus.SYNCED, latency));
                        log.info("Data synced to cloud: key={}, latency={}ms", d.getDataKey(), latency);
                        return d;
                    }))
                    .onErrorResume(e -> {
                        sample.stop(syncLatencyTimer);
                        log.error("Failed to sync data: key={}", d.getDataKey(), e);

                        return Mono.fromCallable(() -> {
                            d.setRetryCount(d.getRetryCount() + 1);
                            if (d.getRetryCount() >= 3) {
                                d.setSyncStatus(SyncStatus.FAILED);
                                syncFailedCounter.increment();
                                pendingSyncGauge.decrementAndGet();
                            } else {
                                d.setSyncStatus(SyncStatus.RETRYING);
                                syncRetriedCounter.increment();
                            }
                            d.setSyncError(e.getMessage());
                            offlineDataMapper.updateById(d);

                            SyncMetrics metrics = syncMetricsMap.get(d.getDataType());
                            if (metrics != null) {
                                metrics.recordFailure(e.getMessage());
                            }

                            emitEvent(new SyncEvent(d.getSyncStatus() == SyncStatus.FAILED ? "sync.failed" : "sync.retrying",
                                    d.getDataKey(), d.getDataType(), d.getSizeBytes(), d.getSyncStatus()));
                            return d;
                        });
                    });
        });
    }

    @Scheduled(fixedDelay = 30000)
    public void retryFailedSyncs() {
        if (!networkAvailable) return;

        Flux.fromIterable(offlineDataMapper.findBySyncStatus(SyncStatus.RETRYING))
                .concatWith(Flux.fromIterable(offlineDataMapper.findBySyncStatus(SyncStatus.PENDING)))
                .flatMap(this::syncToCloud, 3)
                .subscribe();
    }

    @Scheduled(fixedDelay = 10000)
    public void checkNetworkStatus() {
        webClientBuilder.build()
                .get()
                .uri("/actuator/health")
                .retrieve()
                .bodyToMono(String.class)
                .doOnSuccess(r -> {
                    if (!networkAvailable) {
                        networkAvailable = true;
                        log.info("Network restored, starting sync...");
                        emitEvent(new SyncEvent("network.restored", null, null, 0, null));
                        syncAllPending().subscribe();
                    }
                })
                .doOnError(e -> {
                    if (networkAvailable) {
                        networkAvailable = false;
                        log.warn("Network unavailable, switching to offline mode");
                        emitEvent(new SyncEvent("network.lost", null, null, 0, null));
                    }
                })
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    public boolean isNetworkAvailable() {
        return networkAvailable;
    }

    public Mono<Long> getPendingSyncCount() {
        return Mono.fromCallable(() ->
                offlineDataMapper.findBySyncStatus(SyncStatus.PENDING).size() +
                offlineDataMapper.findBySyncStatus(SyncStatus.RETRYING).size()
        );
    }

    public Mono<Map<String, Object>> getDetailedStats() {
        return Mono.fromCallable(() -> {
            CacheStats cacheStats = localCache.stats();
            long pendingCount = offlineDataMapper.findBySyncStatus(SyncStatus.PENDING).size();
            long retryingCount = offlineDataMapper.findBySyncStatus(SyncStatus.RETRYING).size();
            long syncedCount = offlineDataMapper.findBySyncStatus(SyncStatus.SYNCED).size();
            long failedCount = offlineDataMapper.findBySyncStatus(SyncStatus.FAILED).size();

            double successRate = (syncedCount + failedCount) > 0 ?
                    (double) syncedCount / (syncedCount + failedCount) * 100 : 100.0;

            return Map.of(
                    "network", Map.of(
                            "available", networkAvailable
                    ),
                    "data", Map.of(
                            "pending", pendingCount,
                            "retrying", retryingCount,
                            "synced", syncedCount,
                            "failed", failedCount,
                            "successRate", String.format("%.2f%%", successRate)
                    ),
                    "cache", Map.of(
                            "size", localCache.estimatedSize(),
                            "hitCount", cacheStats.hitCount(),
                            "missCount", cacheStats.missCount(),
                            "hitRate", String.format("%.2f%%", cacheStats.hitRate() * 100),
                            "evictionCount", cacheStats.evictionCount()
                    ),
                    "counters", Map.of(
                            "totalSaved", dataSavedCounter.count(),
                            "totalSynced", syncSuccessCounter.count(),
                            "totalFailed", syncFailedCounter.count(),
                            "totalRetried", syncRetriedCounter.count()
                    ),
                    "dataTypes", syncMetricsMap.entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> e.getValue().toMap()
                            ))
            );
        });
    }

    public Flux<SyncEvent> listenSyncEvents() {
        return eventSink.asFlux();
    }

    public Mono<Map<String, Object>> getDataTypeMetrics(String dataType) {
        return Mono.fromCallable(() -> {
            SyncMetrics metrics = syncMetricsMap.get(dataType);
            if (metrics == null) {
                return Map.of("error", "No metrics found for data type: " + dataType);
            }
            return metrics.toMap();
        });
    }

    private String calculateChecksum(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return String.valueOf(payload.hashCode());
        }
    }

    private void emitEvent(SyncEvent event) {
        eventSink.tryEmitNext(event);
    }

    public static class SyncMetrics {
        private final AtomicLong totalSaved = new AtomicLong(0);
        private final AtomicLong totalSynced = new AtomicLong(0);
        private final AtomicLong totalFailed = new AtomicLong(0);
        private final AtomicLong totalBytes = new AtomicLong(0);
        private final AtomicLong totalLatency = new AtomicLong(0);
        private final Map<String, AtomicLong> errorCounts = new ConcurrentHashMap<>();

        public synchronized void recordSaved(long bytes) {
            totalSaved.incrementAndGet();
            totalBytes.addAndGet(bytes);
        }

        public synchronized void recordSuccess(long latencyMs) {
            totalSynced.incrementAndGet();
            totalLatency.addAndGet(latencyMs);
        }

        public synchronized void recordFailure(String errorMessage) {
            totalFailed.incrementAndGet();
            errorCounts.computeIfAbsent(truncateError(errorMessage), k -> new AtomicLong(0)).incrementAndGet();
        }

        private String truncateError(String error) {
            return error != null && error.length() > 100 ? error.substring(0, 100) : error;
        }

        public Map<String, Object> toMap() {
            long synced = totalSynced.get();
            long failed = totalFailed.get();
            double successRate = (synced + failed) > 0 ? (double) synced / (synced + failed) * 100 : 100.0;
            double avgLatency = synced > 0 ? (double) totalLatency.get() / synced : 0;

            return Map.of(
                    "totalSaved", totalSaved.get(),
                    "totalSynced", synced,
                    "totalFailed", failed,
                    "totalBytes", totalBytes.get(),
                    "successRate", String.format("%.2f%%", successRate),
                    "avgLatencyMs", String.format("%.2f", avgLatency),
                    "errorCounts", errorCounts.entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()))
            );
        }
    }

    public record SyncEvent(
            String type,
            String dataKey,
            String dataType,
            long sizeBytes,
            SyncStatus status,
            Long latencyMs
    ) {
        public SyncEvent(String type, String dataKey, String dataType, long sizeBytes, SyncStatus status) {
            this(type, dataKey, dataType, sizeBytes, status, null);
        }
    }
}
