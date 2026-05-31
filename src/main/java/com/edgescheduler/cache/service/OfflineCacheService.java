package com.edgescheduler.cache.service;

import com.edgescheduler.cache.dto.OfflineCacheDataDTO;
import com.edgescheduler.cache.entity.NetworkStatus;
import com.edgescheduler.cache.entity.OfflineCacheData;

import java.util.List;
import java.util.Map;

public interface OfflineCacheService {

    OfflineCacheDataDTO cacheData(OfflineCacheDataDTO cacheDTO);

    OfflineCacheDataDTO getCacheData(String cacheId);

    List<OfflineCacheData> getPendingSyncData(int limit);

    List<OfflineCacheData> getDeviceCacheData(String deviceKey, int limit);

    OfflineCacheDataDTO markAsSyncing(String cacheId);

    OfflineCacheDataDTO markAsSynced(String cacheId);

    OfflineCacheDataDTO markAsFailed(String cacheId, String error);

    List<OfflineCacheData> syncBatchData(int batchSize);

    NetworkStatus updateNetworkStatus(NetworkStatus status);

    NetworkStatus getCurrentNetworkStatus();

    boolean isNetworkOnline();

    void triggerAutoSync();

    int expireOldData(int expireHours);

    Map<String, Object> getCacheStatistics();

    void deleteCacheData(String cacheId);

    void clearSyncedData();
}
