package com.assetmanage.common;

import com.assetmanage.config.LockConfigProperties;
import com.assetmanage.config.LockConfigProperties.LockConfig;
import com.assetmanage.config.LockConfigProperties.LockGranularity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssetLockManager {

    private final Map<String, LockInfo> locks = new ConcurrentHashMap<>();
    private final LockConfigProperties config;

    private static class LockInfo {
        final String lockedBy;
        final LocalDateTime lockedAt;
        final long timeoutMs;
        final String lockKey;

        LockInfo(String lockedBy, LocalDateTime lockedAt, long timeoutMs, String lockKey) {
            this.lockedBy = lockedBy;
            this.lockedAt = lockedAt;
            this.timeoutMs = timeoutMs;
            this.lockKey = lockKey;
        }
    }

    public boolean tryLock(String assetId, String userId, String assetType, 
                           java.math.BigDecimal assetValue, String assetCategory) {
        if (!config.isEnabled()) {
            log.debug("资产锁定已禁用，跳过锁定: assetId={}", assetId);
            return true;
        }

        LockConfig lockConfig = config.getConfigForAsset(assetType, assetValue);
        return tryLockWithRetry(assetId, userId, assetType, assetCategory, lockConfig);
    }

    public boolean tryLock(String assetId, String userId) {
        return tryLock(assetId, userId, "default", java.math.BigDecimal.ZERO, null);
    }

    private boolean tryLockWithRetry(String assetId, String userId, String assetType, 
                                     String assetCategory, LockConfig lockConfig) {
        String lockKey = generateLockKey(assetId, assetType, assetCategory, lockConfig.getGranularity());
        int maxRetries = lockConfig.getMaxRetryCount();
        int retryDelay = lockConfig.getRetryDelayMs();
        long timeoutMs = TimeUnit.SECONDS.toMillis(lockConfig.getTimeoutSeconds());

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                try {
                    log.debug("锁定重试: assetId={}, attempt={}/{}", assetId, attempt, maxRetries);
                    Thread.sleep((long) retryDelay * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            LockInfo existingLock = locks.get(lockKey);
            if (existingLock != null) {
                if (isLockExpired(existingLock)) {
                    log.warn("资产锁定已过期，自动释放: key={}, lockedBy={}", lockKey, existingLock.lockedBy);
                    locks.remove(lockKey);
                } else {
                    log.debug("资产已被锁定: key={}, lockedBy={}, attempt={}", lockKey, existingLock.lockedBy, attempt);
                    if (attempt < maxRetries) {
                        continue;
                    }
                    return false;
                }
            }

            LockInfo newLock = new LockInfo(userId, LocalDateTime.now(), timeoutMs, lockKey);
            LockInfo previous = locks.putIfAbsent(lockKey, newLock);
            if (previous == null) {
                log.debug("资产锁定成功: key={}, userId={}, granularity={}, timeout={}s", 
                        lockKey, userId, lockConfig.getGranularity(), lockConfig.getTimeoutSeconds());
                return true;
            }
        }

        log.warn("资产锁定失败，已达最大重试次数: key={}, maxRetries={}", lockKey, maxRetries);
        return false;
    }

    private String generateLockKey(String assetId, String assetType, String assetCategory, LockGranularity granularity) {
        switch (granularity) {
            case CATEGORY:
                return "cat_" + (assetCategory != null ? assetCategory : assetType);
            case ASSET:
            default:
                return "asset_" + assetId;
        }
    }

    public boolean releaseLock(String assetId, String userId, String assetType, 
                               java.math.BigDecimal assetValue, String assetCategory) {
        if (!config.isEnabled()) {
            return true;
        }

        LockConfig lockConfig = config.getConfigForAsset(assetType, assetValue);
        String lockKey = generateLockKey(assetId, assetType, assetCategory, lockConfig.getGranularity());
        return doReleaseLock(lockKey, userId);
    }

    public boolean releaseLock(String assetId, String userId) {
        if (!config.isEnabled()) {
            return true;
        }
        LockConfig defaultConfig = config.getDefaultConfig();
        String lockKey = generateLockKey(assetId, "default", null, defaultConfig.getGranularity());
        return doReleaseLock(lockKey, userId);
    }

    private boolean doReleaseLock(String lockKey, String userId) {
        LockInfo lock = locks.get(lockKey);
        if (lock == null) {
            log.debug("资产未被锁定: key={}", lockKey);
            return true;
        }
        if (!lock.lockedBy.equals(userId)) {
            log.warn("非锁定者尝试释放锁: key={}, currentUser={}, lockedBy={}",
                    lockKey, userId, lock.lockedBy);
            return false;
        }
        locks.remove(lockKey);
        log.debug("资产锁定释放成功: key={}, userId={}", lockKey, userId);
        return true;
    }

    public boolean isLocked(String assetId, String assetType, java.math.BigDecimal assetValue, String assetCategory) {
        if (!config.isEnabled()) {
            return false;
        }
        LockConfig lockConfig = config.getConfigForAsset(assetType, assetValue);
        String lockKey = generateLockKey(assetId, assetType, assetCategory, lockConfig.getGranularity());
        return isLockedInternal(lockKey);
    }

    public boolean isLocked(String assetId) {
        if (!config.isEnabled()) {
            return false;
        }
        LockConfig defaultConfig = config.getDefaultConfig();
        String lockKey = generateLockKey(assetId, "default", null, defaultConfig.getGranularity());
        return isLockedInternal(lockKey);
    }

    private boolean isLockedInternal(String lockKey) {
        LockInfo lock = locks.get(lockKey);
        if (lock == null) {
            return false;
        }
        if (isLockExpired(lock)) {
            locks.remove(lockKey);
            return false;
        }
        return true;
    }

    public String getLockOwner(String assetId, String assetType, java.math.BigDecimal assetValue, String assetCategory) {
        if (!config.isEnabled()) {
            return null;
        }
        LockConfig lockConfig = config.getConfigForAsset(assetType, assetValue);
        String lockKey = generateLockKey(assetId, assetType, assetCategory, lockConfig.getGranularity());
        return getLockOwnerInternal(lockKey);
    }

    public String getLockOwner(String assetId) {
        if (!config.isEnabled()) {
            return null;
        }
        LockConfig defaultConfig = config.getDefaultConfig();
        String lockKey = generateLockKey(assetId, "default", null, defaultConfig.getGranularity());
        return getLockOwnerInternal(lockKey);
    }

    private String getLockOwnerInternal(String lockKey) {
        LockInfo lock = locks.get(lockKey);
        if (lock == null || isLockExpired(lock)) {
            return null;
        }
        return lock.lockedBy;
    }

    public void forceReleaseLock(String assetId, String assetType, java.math.BigDecimal assetValue, String assetCategory) {
        LockConfig lockConfig = config.getConfigForAsset(assetType, assetValue);
        String lockKey = generateLockKey(assetId, assetType, assetCategory, lockConfig.getGranularity());
        forceReleaseByKey(lockKey);
    }

    public void forceReleaseLock(String assetId) {
        LockConfig defaultConfig = config.getDefaultConfig();
        String lockKey = generateLockKey(assetId, "default", null, defaultConfig.getGranularity());
        forceReleaseByKey(lockKey);
    }

    private void forceReleaseByKey(String lockKey) {
        LockInfo removed = locks.remove(lockKey);
        if (removed != null) {
            log.info("强制释放资产锁定: key={}, lockedBy={}", lockKey, removed.lockedBy);
        }
    }

    public int getActiveLockCount() {
        cleanupExpiredLocks();
        return locks.size();
    }

    public void cleanupExpiredLocks() {
        int removedCount = 0;
        for (Map.Entry<String, LockInfo> entry : locks.entrySet()) {
            if (isLockExpired(entry.getValue())) {
                locks.remove(entry.getKey());
                removedCount++;
                log.warn("清理过期锁: key={}, lockedBy={}", entry.getKey(), entry.getValue().lockedBy);
            }
        }
        if (removedCount > 0) {
            log.info("清理了 {} 个过期锁", removedCount);
        }
    }

    private boolean isLockExpired(LockInfo lock) {
        long elapsed = java.time.Duration.between(lock.lockedAt, LocalDateTime.now()).toMillis();
        return elapsed > lock.timeoutMs;
    }

    public LockConfig getLockConfigForAsset(String assetType, java.math.BigDecimal assetValue) {
        return config.getConfigForAsset(assetType, assetValue);
    }

    public boolean isLockingEnabled() {
        return config.isEnabled();
    }

    public void updateLockConfigForType(String assetType, LockConfig newConfig) {
        if (config.getTypeConfigs() != null) {
            config.getTypeConfigs().put(assetType, newConfig);
            log.info("更新资产类型锁定配置: type={}", assetType);
        }
    }
}
