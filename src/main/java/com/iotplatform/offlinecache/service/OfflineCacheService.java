package com.iotplatform.offlinecache.service;

import com.iotplatform.offlinecache.dto.CachePutDTO;
import com.iotplatform.offlinecache.entity.OfflineCache;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface OfflineCacheService {

    Mono<OfflineCache> put(CachePutDTO dto);

    Mono<Optional<OfflineCache>> get(String cacheKey);

    Mono<Void> delete(String cacheKey);

    Mono<List<OfflineCache>> getUnsynced(int limit);

    Mono<Void> markAsSynced(Long id);

    Mono<Void> markSyncFailed(Long id, String error);

    Mono<Map<String, Long>> getCacheStats();

    Mono<Void> syncToCloud();

    Mono<Boolean> isNetworkAvailable();

    Mono<Void> cleanExpired();

    Mono<Void> putBatch(List<CachePutDTO> dtos);
}
