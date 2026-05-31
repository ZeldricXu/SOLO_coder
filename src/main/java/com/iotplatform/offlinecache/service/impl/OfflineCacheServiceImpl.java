package com.iotplatform.offlinecache.service.impl;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iotplatform.common.exception.BusinessException;
import com.iotplatform.offlinecache.dto.CachePutDTO;
import com.iotplatform.offlinecache.entity.OfflineCache;
import com.iotplatform.offlinecache.mapper.OfflineCacheMapper;
import com.iotplatform.offlinecache.service.OfflineCacheService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfflineCacheServiceImpl implements OfflineCacheService {

    private final OfflineCacheMapper cacheMapper;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final WebClient.Builder webClientBuilder;

    @Value("${iot.offline.buffer-size:10000}")
    private int bufferSize;

    @Value("${iot.offline.sync-interval:30000}")
    private int syncInterval;

    @Value("${iot.offline.retry-attempts:3}")
    private int maxRetryAttempts;

    private final Cache<String, OfflineCache> localCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(Duration.ofHours(24)
            .build();

    private static final String CLOUD_SYNC_URL = "https://api.iot-platform.com/api/v1/sync";
    private static final String NETWORK_CHECK_URL = "https://www.baidu.com";

    @Override
    @Transactional
    public Mono<OfflineCache> put(CachePutDTO dto) {
        Timer.Sample sample = Timer.start(meterRegistry);
        return Mono.fromCallable(() -> {
            try {
                LocalDateTime now = LocalDateTime.now();
                OfflineCache cache = new OfflineCache();
                cache.setCacheKey(dto.getCacheKey());
                cache.setCacheValue(dto.getCacheValue());
                cache.setDataType(dto.getDataType());
                cache.setSynced(false);
                cache.setSyncAttempts(0);
                cache.setCreatedAt(now);
                cache.setUpdatedAt(now);

                if (dto.getTtlSeconds() != null) {
                    cache.setExpireAt(now.plusSeconds(dto.getTtlSeconds()));
                } else if (dto.getExpireAt() != null) {
                    cache.setExpireAt(dto.getExpireAt());
                }

                cacheMapper.insert(cache);
                localCache.put(dto.getCacheKey(), cache);

                log.debug("Cache put: {}", dto.getCacheKey());
                meterRegistry.counter("offlinecache.put").increment();

                isNetworkAvailable().subscribe(available -> {
                    if (available) {
                        syncSingleCache(cache).subscribe();
                    }
                });

                return cache;
            } catch (Exception e) {
                log.error("Failed to put cache: {}", e.getMessage(), e);
                meterRegistry.counter("offlinecache.put.failed").increment();
                throw new BusinessException("缓存写入失败: " + e.getMessage());
            } finally {
                sample.stop(meterRegistry.timer("offlinecache.put.latency"));
            }
        });
    }

    @Override
    public Mono<Optional<OfflineCache>> get(String cacheKey) {
        return Mono.fromCallable(() -> {
            OfflineCache cached = localCache.getIfPresent(cacheKey);
            if (cached != null) {
                meterRegistry.counter("offlinecache.cache.hit").increment();
                return Optional.of(cached);
            }

            meterRegistry.counter("offlinecache.cache.miss").increment();
            return cacheMapper.findByCacheKey(cacheKey);
        });
    }

    @Override
    @Transactional
    public Mono<Void> delete(String cacheKey) {
        return Mono.fromCallable(() -> {
            cacheMapper.findByCacheKey(cacheKey).ifPresent(cache -> {
                cacheMapper.deleteById(cache.getId());
                localCache.invalidate(cacheKey);
            });
            log.debug("Cache deleted: {}", cacheKey);
            return null;
        });
    }

    @Override
    public Mono<List<OfflineCache>> getUnsynced(int limit) {
        return Mono.fromCallable(() -> cacheMapper.findUnsynced(maxRetryAttempts, limit);
    }

    @Override
    @Transactional
    public Mono<Void> markAsSynced(Long id) {
        return Mono.fromCallable(() -> {
            cacheMapper.markAsSynced(id, LocalDateTime.now());
            return null;
        });
    }

    @Override
    @Transactional
    public Mono<Void> markSyncFailed(Long id, String error) {
        return Mono.fromCallable(() -> {
            cacheMapper.markSyncFailed(id, LocalDateTime.now(), error);
            return null;
        });
    }

    @Override
    public Mono<Map<String, Long>> getCacheStats() {
        return Mono.fromCallable(() -> {
            Map<String, Long> stats = new HashMap<>();
            stats.put("total", cacheMapper.selectCount(null));
            stats.put("unsynced", cacheMapper.countUnsynced());
            stats.put("syncFailed", cacheMapper.countSyncFailed(maxRetryAttempts));
            stats.put("localCacheSize", (long) localCache.estimatedSize());
            return stats;
        });
    }

    @Override
    public Mono<Void> syncToCloud() {
        return isNetworkAvailable()
                .flatMap(available -> {
                    if (!available) {
                        log.debug("Network not available, skipping sync");
                        return Mono.empty();
                    }
                    return getUnsynced(100)
                            .flatMapMany(Flux::fromIterable)
                            .flatMap(this::syncSingleCache)
                            .then();
                });
    }

    @Override
    public Mono<Boolean> isNetworkAvailable() {
        return webClientBuilder.build()
                .get()
                .uri(NETWORK_CHECK_URL)
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getStatusCode().is2xxSuccessful())
                .onErrorReturn(false)
                .defaultIfEmpty(false);
    }

    @Override
    @Transactional
    public Mono<Void> cleanExpired() {
        return Mono.fromCallable(() -> {
            int deleted = cacheMapper.deleteExpired(LocalDateTime.now());
            log.info("Cleaned {} expired cache entries", deleted);
            meterRegistry.counter("offlinecache.cleaned").increment(deleted);
            return null;
        });
    }

    @Override
    public Mono<Void> putBatch(List<CachePutDTO> dtos) {
        return Flux.fromIterable(dtos)
                .flatMap(this::put)
                .then();
    }

    private Mono<Void> syncSingleCache(OfflineCache cache) {
        return webClientBuilder.build()
                .post()
                .uri(CLOUD_SYNC_URL)
                .bodyValue(cache)
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(response -> {
                    log.debug("Cache synced to cloud: {}", cache.getCacheKey());
                    return markAsSynced(cache.getId());
                })
                .onErrorResume(e -> {
                    log.error("Failed to sync cache {}: {}", cache.getCacheKey(), e.getMessage());
                    return markSyncFailed(cache.getId(), e.getMessage());
                });
    }

    @Scheduled(fixedRateString = "${iot.offline.sync-interval:30000}")
    public void scheduledSync() {
        syncToCloud().subscribe();
    }

    @Scheduled(cron = "0 0 * * * ?")
    public void scheduledCleanup() {
        cleanExpired().subscribe();
    }
}
