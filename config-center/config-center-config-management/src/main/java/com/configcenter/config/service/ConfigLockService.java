package com.configcenter.config.service;

import com.configcenter.common.exception.BusinessException;
import com.configcenter.config.config.LockProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigLockService {

    private final LockProperties properties;

    private final Map<String, ConfigLock> lockMap = new ConcurrentHashMap<>();

    public boolean acquireLock(String configId) {
        return acquireLock(configId, properties.getAcquireTimeoutMillis());
    }

    public boolean acquireLock(String configId, long timeoutMillis) {
        if (!properties.getEnabled()) {
            return true;
        }

        ConfigLock lock = lockMap.computeIfAbsent(configId, k -> new ConfigLock(k, properties.getHoldTimeoutMillis()));

        try {
            boolean acquired = lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS);
            if (acquired) {
                log.debug("Lock acquired: configId={}, holder={}", configId, lock.getHolder());
            } else {
                log.warn("Failed to acquire lock: configId={}, timeout={}ms", configId, timeoutMillis);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Lock acquisition interrupted: configId={}", configId, e);
            return false;
        }
    }

    public void releaseLock(String configId) {
        if (!properties.getEnabled()) {
            return;
        }

        ConfigLock lock = lockMap.get(configId);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.debug("Lock released: configId={}", configId);
        }
    }

    public boolean isLocked(String configId) {
        ConfigLock lock = lockMap.get(configId);
        return lock != null && lock.isLocked();
    }

    public <T> T executeWithLock(String configId, LockCallback<T> callback) {
        if (!properties.getEnabled()) {
            try {
                return callback.execute();
            } catch (Exception e) {
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new BusinessException("执行失败: " + e.getMessage(), e);
            }
        }

        boolean acquired = false;
        int attempts = 0;
        
        while (attempts < properties.getRetryCount() && !acquired) {
            acquired = acquireLock(configId);
            if (!acquired) {
                attempts++;
                if (attempts < properties.getRetryCount()) {
                    try {
                        Thread.sleep(properties.getRetryIntervalMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new BusinessException("获取锁被中断");
                    }
                }
            }
        }

        if (!acquired) {
            throw new BusinessException("无法获取配置更新锁，请稍后重试: configId=" + configId);
        }

        try {
            return callback.execute();
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new BusinessException("执行失败: " + e.getMessage(), e);
        } finally {
            releaseLock(configId);
        }
    }

    public Map<String, Object> getLockStatus(String configId) {
        Map<String, Object> status = new java.util.HashMap<>();
        ConfigLock lock = lockMap.get(configId);
        
        if (lock == null) {
            status.put("exists", false);
            status.put("locked", false);
        } else {
            status.put("exists", true);
            status.put("locked", lock.isLocked());
            status.put("holder", lock.getHolder());
            status.put("acquiredAt", lock.getAcquiredAt());
            status.put("holdCount", lock.getHoldCount());
            status.put("hasExpired", lock.hasExpired());
        }
        
        status.put("lockEnabled", properties.getEnabled());
        status.put("lockPrefix", properties.getLockPrefix());
        status.put("acquireTimeout", properties.getAcquireTimeoutMillis());
        status.put("holdTimeout", properties.getHoldTimeoutMillis());
        
        return status;
    }

    public Map<String, Object> getAllLockStatus() {
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("totalLocks", lockMap.size());
        result.put("lockedCount", lockMap.values().stream().filter(ConfigLock::isLocked).count());
        
        Map<String, Map<String, Object>> details = new java.util.HashMap<>();
        for (Map.Entry<String, ConfigLock> entry : lockMap.entrySet()) {
            ConfigLock lock = entry.getValue();
            Map<String, Object> detail = new java.util.HashMap<>();
            detail.put("locked", lock.isLocked());
            detail.put("holder", lock.getHolder());
            detail.put("acquiredAt", lock.getAcquiredAt());
            detail.put("hasExpired", lock.hasExpired());
            details.put(entry.getKey(), detail);
        }
        result.put("locks", details);
        
        return result;
    }

    public void cleanupExpiredLocks() {
        int cleaned = 0;
        for (Map.Entry<String, ConfigLock> entry : lockMap.entrySet()) {
            ConfigLock lock = entry.getValue();
            if (lock.hasExpired() && !lock.isLocked()) {
                lockMap.remove(entry.getKey());
                cleaned++;
            }
        }
        if (cleaned > 0) {
            log.info("Cleaned up {} expired locks", cleaned);
        }
    }

    @Getter
    public static class ConfigLock {
        private final String configId;
        private final long holdTimeoutMillis;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile String holder;
        private volatile LocalDateTime acquiredAt;
        private final AtomicInteger acquireCount = new AtomicInteger(0);

        public ConfigLock(String configId, long holdTimeoutMillis) {
            this.configId = configId;
            this.holdTimeoutMillis = holdTimeoutMillis;
        }

        public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
            boolean acquired = lock.tryLock(timeout, unit);
            if (acquired) {
                this.holder = Thread.currentThread().getName();
                this.acquiredAt = LocalDateTime.now();
                this.acquireCount.incrementAndGet();
            }
            return acquired;
        }

        public void unlock() {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                if (!lock.isLocked()) {
                    this.holder = null;
                }
            }
        }

        public boolean isLocked() {
            return lock.isLocked();
        }

        public boolean isHeldByCurrentThread() {
            return lock.isHeldByCurrentThread();
        }

        public int getHoldCount() {
            return lock.getHoldCount();
        }

        public boolean hasExpired() {
            if (acquiredAt == null) {
                return false;
            }
            long elapsed = java.time.Duration.between(acquiredAt, LocalDateTime.now()).toMillis();
            return elapsed > holdTimeoutMillis && !isLocked();
        }
    }

    @FunctionalInterface
    public interface LockCallback<T> {
        T execute() throws Exception;
    }
}
