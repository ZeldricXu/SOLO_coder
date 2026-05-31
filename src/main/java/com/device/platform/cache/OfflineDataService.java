package com.device.platform.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.device.platform.common.BusinessException;
import com.device.platform.common.JsonUtils;
import com.device.platform.common.TraceContext;
import com.device.platform.dto.OfflineDataRequest;
import com.device.platform.entity.OfflineData;
import com.device.platform.mapper.OfflineDataMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfflineDataService {

    private final OfflineDataMapper offlineDataMapper;
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    @Value("${offline.cache.max-size:10000}")
    private int maxCacheSize;

    @Value("${offline.sync.batch-size:100}")
    private int syncBatchSize;

    @Value("${offline.sync.max-retry:3}")
    private int maxRetry;

    private final Cache<String, Object> localCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    private volatile boolean networkAvailable = true;

    @Transactional
    public Mono<OfflineData> cacheOfflineData(OfflineDataRequest request, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            ctx.putAttribute("deviceId", request.getDeviceId());
            ctx.putAttribute("dataType", request.getDataType());

            String checksum = request.getChecksum();
            if (checksum == null || checksum.isEmpty()) {
                checksum = calculateChecksum(JsonUtils.toJson(request.getPayload()));
            }

            OfflineData offlineData = new OfflineData();
            offlineData.setDataId(generateDataId());
            offlineData.setDeviceId(request.getDeviceId());
            offlineData.setDataType(request.getDataType());
            offlineData.setPayload(JsonUtils.toJson(request.getPayload()));
            offlineData.setCollectedAt(request.getCollectedAt());
            offlineData.setSynced(false);
            offlineData.setSyncAttempts(0);
            offlineData.setChecksum(checksum);

            offlineDataMapper.insert(offlineData);

            if (networkAvailable) {
                syncSingleData(offlineData, ctx)
                        .doOnError(e -> log.warn("实时同步失败，数据将在后台重试: dataId={}, error={}",
                                offlineData.getDataId(), e.getMessage()))
                        .onErrorResume(e -> Mono.empty())
                        .subscribe();
            }

            localCache.put(offlineData.getDataId(), offlineData);

            log.info("离线数据已缓存: dataId={}, deviceId={}, dataType={}, traceId={}",
                    offlineData.getDataId(), request.getDeviceId(), request.getDataType(), ctx.getTraceId());

            return offlineData;
        });
    }

    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void syncPendingData() {
        if (!networkAvailable) {
            log.debug("网络不可用，跳过离线数据同步");
            return;
        }

        List<OfflineData> pendingData = offlineDataMapper.selectList(new LambdaQueryWrapper<OfflineData>()
                .eq(OfflineData::isSynced, false)
                .lt(OfflineData::getSyncAttempts, maxRetry)
                .orderByAsc(OfflineData::getCreatedAt)
                .last("LIMIT " + syncBatchSize));

        if (pendingData.isEmpty()) {
            return;
        }

        log.info("开始同步离线数据: count={}", pendingData.size());

        TraceContext ctx = new TraceContext();
        int successCount = 0;

        for (OfflineData data : pendingData) {
            try {
                syncDataToCloud(data).block();
                markAsSynced(data, ctx);
                successCount++;
            } catch (Exception e) {
                handleSyncFailure(data, e);
            }
        }

        log.info("离线数据同步完成: total={}, success={}, failed={}, traceId={}",
                pendingData.size(), successCount, pendingData.size() - successCount, ctx.getTraceId());
    }

    private Mono<Void> syncSingleData(OfflineData data, TraceContext ctx) {
        return syncDataToCloud(data)
                .doOnSuccess(v -> markAsSynced(data, ctx))
                .doOnError(e -> handleSyncFailure(data, e))
                .then();
    }

    private Mono<Void> syncDataToCloud(OfflineData data) {
        String redisKey = "offline:sync:" + data.getDataId();
        return redisTemplate.opsForValue().set(redisKey, data.getPayload(), Duration.ofHours(24))
                .doOnSuccess(v -> {
                    String queueKey = "offline:queue";
                    redisTemplate.opsForList().rightPush(queueKey, data.getDataId()).subscribe();
                    log.debug("数据已同步到云端队列: dataId={}", data.getDataId());
                })
                .then();
    }

    @Transactional
    protected void markAsSynced(OfflineData data, TraceContext ctx) {
        offlineDataMapper.update(null, new LambdaUpdateWrapper<OfflineData>()
                .eq(OfflineData::getId, data.getId())
                .set(OfflineData::isSynced, true)
                .set(OfflineData::getSyncedAt, Instant.now())
                .set(OfflineData::getSyncError, null));

        localCache.invalidate(data.getDataId());
    }

    @Transactional
    protected void handleSyncFailure(OfflineData data, Throwable e) {
        int newAttempts = data.getSyncAttempts() != null ? data.getSyncAttempts() + 1 : 1;

        offlineDataMapper.update(null, new LambdaUpdateWrapper<OfflineData>()
                .eq(OfflineData::getId, data.getId())
                .set(OfflineData::getSyncAttempts, newAttempts)
                .set(OfflineData::getSyncError, e.getMessage()));

        log.warn("数据同步失败: dataId={}, attempts={}, error={}",
                data.getDataId(), newAttempts, e.getMessage());
    }

    public Flux<OfflineData> getPendingData(String deviceId, TraceContext ctx) {
        return Flux.fromIterable(offlineDataMapper.selectList(new LambdaQueryWrapper<OfflineData>()
                .eq(deviceId != null, OfflineData::getDeviceId, deviceId)
                .eq(OfflineData::isSynced, false)
                .orderByDesc(OfflineData::getCreatedAt)));
    }

    public Mono<Long> getPendingCount(String deviceId, TraceContext ctx) {
        return Mono.fromCallable(() -> offlineDataMapper.selectCount(new LambdaQueryWrapper<OfflineData>()
                .eq(deviceId != null, OfflineData::getDeviceId, deviceId)
                .eq(OfflineData::isSynced, false)));
    }

    @Transactional
    public Mono<Void> setNetworkStatus(boolean available, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            this.networkAvailable = available;
            log.info("网络状态已更新: available={}, traceId={}", available, ctx.getTraceId());

            if (available) {
                syncPendingData();
            }
            return null;
        });
    }

    public Mono<Boolean> checkNetworkStatus(TraceContext ctx) {
        return Mono.fromCallable(() -> networkAvailable);
    }

    private String calculateChecksum(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(data.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new BusinessException(500, "校验和计算失败");
        }
    }

    private String generateDataId() {
        return "data_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}
