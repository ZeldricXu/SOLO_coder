package com.edgescheduler.cache.service.impl;

import cn.hutool.core.util.IdUtil;
import com.edgescheduler.cache.dto.OfflineCacheDataDTO;
import com.edgescheduler.cache.entity.NetworkStatus;
import com.edgescheduler.cache.entity.OfflineCacheData;
import com.edgescheduler.cache.mapper.NetworkStatusMapper;
import com.edgescheduler.cache.mapper.OfflineCacheDataMapper;
import com.edgescheduler.cache.service.OfflineCacheService;
import com.edgescheduler.common.exception.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfflineCacheServiceImpl implements OfflineCacheService {

    private final OfflineCacheDataMapper cacheMapper;
    private final NetworkStatusMapper networkMapper;
    private final MeterRegistry meterRegistry;

    @Value("${edge.scheduler.cache.max-sync-attempts:5}")
    private int maxSyncAttempts;

    @Value("${edge.scheduler.cache.expire-hours:72}")
    private int defaultExpireHours;

    @Override
    @Transactional
    public OfflineCacheDataDTO cacheData(OfflineCacheDataDTO cacheDTO) {
        OfflineCacheData cache = new OfflineCacheData();
        BeanUtils.copyProperties(cacheDTO, cache);
        cache.setCacheId("cache_" + IdUtil.getSnowflakeNextIdStr());
        cache.setStatus(OfflineCacheData.Status.PENDING);
        cache.setCachedAt(LocalDateTime.now());
        cache.setSyncAttempts(0);
        cache.setPriority(cacheDTO.getPriority() != null ? cacheDTO.getPriority() : 50);

        if (cache.getPayload() != null) {
            cache.setPayloadSize((long) cache.getPayload().toString().getBytes().length);
        }

        cacheMapper.insert(cache);
        meterRegistry.counter("cache.data.total").increment();
        log.debug("Data cached: {}, size: {} bytes", cache.getCacheId(), cache.getPayloadSize());

        return convertToDTO(cache);
    }

    @Override
    public OfflineCacheDataDTO getCacheData(String cacheId) {
        OfflineCacheData cache = getCacheEntity(cacheId);
        return convertToDTO(cache);
    }

    @Override
    public List<OfflineCacheData> getPendingSyncData(int limit) {
        return cacheMapper.selectPendingSync(OfflineCacheData.Status.PENDING, limit);
    }

    @Override
    public List<OfflineCacheData> getDeviceCacheData(String deviceKey, int limit) {
        return cacheMapper.selectByDeviceKey(deviceKey, limit);
    }

    @Override
    @Transactional
    public OfflineCacheDataDTO markAsSyncing(String cacheId) {
        OfflineCacheData cache = getCacheEntity(cacheId);
        cache.setStatus(OfflineCacheData.Status.SYNCING);
        cache.setSyncAttempts(cache.getSyncAttempts() + 1);
        cacheMapper.updateById(cache);
        return convertToDTO(cache);
    }

    @Override
    @Transactional
    public OfflineCacheDataDTO markAsSynced(String cacheId) {
        OfflineCacheData cache = getCacheEntity(cacheId);
        cache.setStatus(OfflineCacheData.Status.SYNCED);
        cache.setSyncedAt(LocalDateTime.now());
        cacheMapper.updateById(cache);
        meterRegistry.counter("cache.sync.success.total").increment();
        log.debug("Data synced successfully: {}", cacheId);
        return convertToDTO(cache);
    }

    @Override
    @Transactional
    public OfflineCacheDataDTO markAsFailed(String cacheId, String error) {
        OfflineCacheData cache = getCacheEntity(cacheId);
        cache.setLastSyncError(error);
        if (cache.getSyncAttempts() >= maxSyncAttempts) {
            cache.setStatus(OfflineCacheData.Status.FAILED);
            log.warn("Data sync failed after {} attempts: {}", maxSyncAttempts, cacheId);
        } else {
            cache.setStatus(OfflineCacheData.Status.PENDING);
        }
        cacheMapper.updateById(cache);
        meterRegistry.counter("cache.sync.failed.total").increment();
        return convertToDTO(cache);
    }

    @Override
    @Transactional
    public List<OfflineCacheData> syncBatchData(int batchSize) {
        if (!isNetworkOnline()) {
            log.debug("Network is offline, skipping sync");
            return List.of();
        }

        List<OfflineCacheData> pendingData = getPendingSyncData(batchSize);
        int syncedCount = 0;

        for (OfflineCacheData data : pendingData) {
            try {
                markAsSyncing(data.getCacheId());
                boolean success = simulateSyncToCloud(data);
                if (success) {
                    markAsSynced(data.getCacheId());
                    syncedCount++;
                } else {
                    markAsFailed(data.getCacheId(), "Sync failed");
                }
            } catch (Exception e) {
                markAsFailed(data.getCacheId(), e.getMessage());
            }
        }

        log.info("Batch sync completed: {}/{} succeeded", syncedCount, pendingData.size());
        return pendingData;
    }

    private boolean simulateSyncToCloud(OfflineCacheData data) {
        log.debug("Simulating sync to cloud for: {}", data.getCacheId());
        return true;
    }

    @Override
    @Transactional
    public NetworkStatus updateNetworkStatus(NetworkStatus status) {
        status.setStatusId("net_" + IdUtil.getSnowflakeNextIdStr());
        networkMapper.insert(status);

        if (NetworkStatus.Status.ONLINE.equals(status.getStatus())) {
            meterRegistry.counter("network.online.total").increment();
        } else if (NetworkStatus.Status.OFFLINE.equals(status.getStatus())) {
            meterRegistry.counter("network.offline.total").increment();
        }

        log.info("Network status updated: {}", status.getStatus());
        return status;
    }

    @Override
    public NetworkStatus getCurrentNetworkStatus() {
        return networkMapper.selectLatestStatus();
    }

    @Override
    public boolean isNetworkOnline() {
        NetworkStatus status = getCurrentNetworkStatus();
        return status != null && NetworkStatus.Status.ONLINE.equals(status.getStatus());
    }

    @Override
    @Scheduled(fixedDelayString = "${edge.scheduler.cache.sync-interval-ms:30000}")
    public void triggerAutoSync() {
        if (isNetworkOnline()) {
            syncBatchData(100);
        }
    }

    @Override
    @Transactional
    @Scheduled(cron = "${edge.scheduler.cache.expire-cron:0 0 * * * ?}")
    public int expireOldData(int expireHours) {
        int hours = expireHours > 0 ? expireHours : defaultExpireHours;
        LocalDateTime expireTime = LocalDateTime.now().minusHours(hours);
        int expiredCount = cacheMapper.expireOldData(expireTime);
        if (expiredCount > 0) {
            log.info("Expired {} old cache data entries", expiredCount);
            meterRegistry.counter("cache.expire.total").increment(expiredCount);
        }
        return expiredCount;
    }

    @Override
    public Map<String, Object> getCacheStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("pendingCount", cacheMapper.countByStatus(OfflineCacheData.Status.PENDING));
        stats.put("syncingCount", cacheMapper.countByStatus(OfflineCacheData.Status.SYNCING));
        stats.put("syncedCount", cacheMapper.countByStatus(OfflineCacheData.Status.SYNCED));
        stats.put("failedCount", cacheMapper.countByStatus(OfflineCacheData.Status.FAILED));
        stats.put("expiredCount", cacheMapper.countByStatus(OfflineCacheData.Status.EXPIRED));

        Long pendingSize = cacheMapper.sumPayloadSizeByStatus(OfflineCacheData.Status.PENDING);
        stats.put("pendingSizeBytes", pendingSize != null ? pendingSize : 0);

        NetworkStatus netStatus = getCurrentNetworkStatus();
        stats.put("networkStatus", netStatus != null ? netStatus.getStatus() : NetworkStatus.Status.UNKNOWN);
        stats.put("networkOnline", isNetworkOnline());

        return stats;
    }

    @Override
    @Transactional
    public void deleteCacheData(String cacheId) {
        OfflineCacheData cache = getCacheEntity(cacheId);
        cacheMapper.deleteById(cache.getId());
        log.debug("Cache data deleted: {}", cacheId);
    }

    @Override
    @Transactional
    public void clearSyncedData() {
        List<OfflineCacheData> syncedData = cacheMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OfflineCacheData>()
                        .eq(OfflineCacheData::getStatus, OfflineCacheData.Status.SYNCED));
        for (OfflineCacheData data : syncedData) {
            cacheMapper.deleteById(data.getId());
        }
        log.info("Cleared {} synced cache entries", syncedData.size());
    }

    private OfflineCacheData getCacheEntity(String cacheId) {
        OfflineCacheData cache = cacheMapper.selectByCacheId(cacheId);
        if (cache == null) {
            throw BusinessException.notFound("Cache data not found: " + cacheId);
        }
        return cache;
    }

    private OfflineCacheDataDTO convertToDTO(OfflineCacheData cache) {
        OfflineCacheDataDTO dto = new OfflineCacheDataDTO();
        BeanUtils.copyProperties(cache, dto);
        return dto;
    }
}
