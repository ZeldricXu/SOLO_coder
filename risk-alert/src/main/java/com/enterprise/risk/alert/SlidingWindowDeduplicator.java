package com.enterprise.risk.alert;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class SlidingWindowDeduplicator {

    @Value("${risk.alert.deduplication.window-seconds:300}")
    private int defaultWindowSeconds;

    @Value("${risk.alert.deduplication.max-cache-size:100000}")
    private long maxCacheSize;

    private final Cache<String, DeduplicationEntry> localCache;

    public SlidingWindowDeduplicator() {
        this.localCache = CacheBuilder.newBuilder()
                .maximumSize(100000)
                .expireAfterWrite(300, TimeUnit.SECONDS)
                .removalListener(notification -> {
                    if (log.isDebugEnabled()) {
                        log.debug("去重缓存条目已过期: {}", notification.getKey());
                    }
                })
                .build();
    }

    public Optional<DeduplicationResult> checkAndRecord(String fingerprint) {
        return checkAndRecord(fingerprint, defaultWindowSeconds);
    }

    public Optional<DeduplicationResult> checkAndRecord(String fingerprint,
                                                        int windowSeconds) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return Optional.of(new DeduplicationResult(false, null, 0));
        }

        DeduplicationEntry existing = localCache.getIfPresent(fingerprint);
        long now = System.currentTimeMillis();

        if (existing != null && !isExpired(existing, now)) {
            existing.hitCount++;
            existing.lastHitTime = now;
            localCache.put(fingerprint, existing);

            if (log.isDebugEnabled()) {
                log.debug("指纹命中去重: {}, 命中次数={}",
                        fingerprint.substring(0, Math.min(16, fingerprint.length())),
                        existing.hitCount);
            }

            return Optional.of(new DeduplicationResult(
                    true,
                    existing.alertId,
                    existing.hitCount
            ));
        }

        DeduplicationEntry newEntry = new DeduplicationEntry();
        newEntry.firstHitTime = now;
        newEntry.lastHitTime = now;
        newEntry.hitCount = 1;
        newEntry.windowSeconds = windowSeconds;
        localCache.put(fingerprint, newEntry);

        return Optional.of(new DeduplicationResult(false, null, 1));
    }

    public void bindAlertId(String fingerprint, String alertId) {
        if (fingerprint == null || alertId == null) {
            return;
        }
        DeduplicationEntry entry = localCache.getIfPresent(fingerprint);
        if (entry != null) {
            entry.alertId = alertId;
            localCache.put(fingerprint, entry);
            log.debug("指纹已绑定告警ID: {} -> {}", fingerprint, alertId);
        } else {
            DeduplicationEntry newEntry = new DeduplicationEntry();
            newEntry.firstHitTime = System.currentTimeMillis();
            newEntry.lastHitTime = newEntry.firstHitTime;
            newEntry.hitCount = 1;
            newEntry.alertId = alertId;
            newEntry.windowSeconds = defaultWindowSeconds;
            localCache.put(fingerprint, newEntry);
        }
    }

    public boolean isDuplicated(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return false;
        }
        DeduplicationEntry entry = localCache.getIfPresent(fingerprint);
        if (entry == null) {
            return false;
        }
        return !isExpired(entry, System.currentTimeMillis());
    }

    public int getHitCount(String fingerprint) {
        DeduplicationEntry entry = localCache.getIfPresent(fingerprint);
        return entry != null ? entry.hitCount : 0;
    }

    public String getBoundAlertId(String fingerprint) {
        DeduplicationEntry entry = localCache.getIfPresent(fingerprint);
        return entry != null ? entry.alertId : null;
    }

    public void evict(String fingerprint) {
        if (fingerprint != null) {
            localCache.invalidate(fingerprint);
            log.debug("已从去重缓存移除: {}", fingerprint);
        }
    }

    public void clearAll() {
        long size = localCache.size();
        localCache.invalidateAll();
        log.info("已清空去重缓存，共清除 {} 条", size);
    }

    public long getCacheSize() {
        return localCache.size();
    }

    public void cleanupExpired() {
        long before = localCache.size();
        localCache.cleanUp();
        long after = localCache.size();
        if (before > after) {
            log.debug("清理去重缓存: {} -> {}", before, after);
        }
    }

    private boolean isExpired(DeduplicationEntry entry, long now) {
        long windowMs = (long) entry.windowSeconds * 1000L;
        return (now - entry.firstHitTime) > windowMs;
    }

    public static class DeduplicationEntry {
        String alertId;
        long firstHitTime;
        long lastHitTime;
        int hitCount;
        int windowSeconds;
    }

    public static class DeduplicationResult {
        private final boolean duplicated;
        private final String existingAlertId;
        private final int hitCount;

        public DeduplicationResult(boolean duplicated,
                                   String existingAlertId,
                                   int hitCount) {
            this.duplicated = duplicated;
            this.existingAlertId = existingAlertId;
            this.hitCount = hitCount;
        }

        public boolean isDuplicated() {
            return duplicated;
        }

        public String getExistingAlertId() {
            return existingAlertId;
        }

        public int getHitCount() {
            return hitCount;
        }
    }

    public int getDefaultWindowSeconds() {
        return defaultWindowSeconds;
    }

    public void setDefaultWindowSeconds(int seconds) {
        this.defaultWindowSeconds = seconds;
    }
}
