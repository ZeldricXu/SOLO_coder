package com.fitnesscenter.lock;

import com.fitnesscenter.config.LockConfig;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class DistributedLock {

    private static final ConcurrentHashMap<String, LockInfo> lockMap = new ConcurrentHashMap<>();

    private final LockConfig lockConfig;

    private final ReentrantLock internalLock = new ReentrantLock();

    public DistributedLock(LockConfig lockConfig) {
        this.lockConfig = lockConfig;
    }

    public DistributedLock() {
        this.lockConfig = new LockConfig();
    }

    public boolean tryAcquireLock(String lockKey, String memberLevel) {
        long timeout = lockConfig.getTimeoutByLevel(memberLevel);
        return tryAcquireLock(lockKey, timeout, TimeUnit.MILLISECONDS);
    }

    public boolean tryAcquireLock(String lockKey, long timeout, TimeUnit unit) {
        internalLock.lock();
        try {
            LockInfo existingLock = lockMap.get(lockKey);
            if (existingLock != null && !existingLock.isExpired()) {
                return false;
            }

            LockInfo newLock = new LockInfo(lockKey, System.currentTimeMillis(), unit.toMillis(timeout));
            lockMap.put(lockKey, newLock);
            return true;
        } finally {
            internalLock.unlock();
        }
    }

    public boolean releaseLock(String lockKey) {
        internalLock.lock();
        try {
            LockInfo lock = lockMap.remove(lockKey);
            return lock != null;
        } finally {
            internalLock.unlock();
        }
    }

    public boolean isLocked(String lockKey) {
        internalLock.lock();
        try {
            LockInfo lock = lockMap.get(lockKey);
            return lock != null && !lock.isExpired();
        } finally {
            internalLock.unlock();
        }
    }

    public long getLockTimeout(String memberLevel) {
        return lockConfig.getTimeoutByLevel(memberLevel);
    }

    public void clearAllLocks() {
        internalLock.lock();
        try {
            lockMap.clear();
        } finally {
            internalLock.unlock();
        }
    }

    public int getActiveLockCount() {
        internalLock.lock();
        try {
            int count = 0;
            for (LockInfo lock : lockMap.values()) {
                if (!lock.isExpired()) {
                    count++;
                }
            }
            return count;
        } finally {
            internalLock.unlock();
        }
    }

    public LockConfig getLockConfig() {
        return lockConfig;
    }

    public static class LockInfo {
        private final String key;
        private final long acquiredAt;
        private final long timeoutMs;

        public LockInfo(String key, long acquiredAt, long timeoutMs) {
            this.key = key;
            this.acquiredAt = acquiredAt;
            this.timeoutMs = timeoutMs;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > acquiredAt + timeoutMs;
        }

        public String getKey() {
            return key;
        }

        public long getAcquiredAt() {
            return acquiredAt;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }
    }
}
