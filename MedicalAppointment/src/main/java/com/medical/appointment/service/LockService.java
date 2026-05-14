package com.medical.appointment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class LockService {

    private static final Logger log = LoggerFactory.getLogger(LockService.class);

    private static final long VIP_PATIENT_TIMEOUT_SECONDS = 5;
    private static final long NORMAL_PATIENT_TIMEOUT_SECONDS = 15;
    private static final long DEFAULT_TIMEOUT_SECONDS = 10;

    private final Map<String, LockEntry> locks = new ConcurrentHashMap<>();

    public static class LockResult {
        private final boolean acquired;
        private final String lockKey;
        private final String message;
        private final Duration waitTime;

        public LockResult(boolean acquired, String lockKey, String message, Duration waitTime) {
            this.acquired = acquired;
            this.lockKey = lockKey;
            this.message = message;
            this.waitTime = waitTime;
        }

        public boolean isAcquired() {
            return acquired;
        }

        public String getLockKey() {
            return lockKey;
        }

        public String getMessage() {
            return message;
        }

        public Duration getWaitTime() {
            return waitTime;
        }
    }

    public static class LockEntry {
        private final String owner;
        private final LocalDateTime acquiredAt;
        private final LocalDateTime expiresAt;
        private final AtomicBoolean locked = new AtomicBoolean(true);

        public LockEntry(String owner, Duration timeout) {
            this.owner = owner;
            this.acquiredAt = LocalDateTime.now();
            this.expiresAt = LocalDateTime.now().plus(timeout);
        }

        public String getOwner() {
            return owner;
        }

        public LocalDateTime getAcquiredAt() {
            return acquiredAt;
        }

        public LocalDateTime getExpiresAt() {
            return expiresAt;
        }

        public boolean isLocked() {
            return locked.get() && LocalDateTime.now().isBefore(expiresAt);
        }

        public void release() {
            locked.set(false);
        }

        public boolean isExpired() {
            return LocalDateTime.now().isAfter(expiresAt);
        }
    }

    public String buildScheduleLockKey(String scheduleId) {
        return "SCHEDULE_" + scheduleId;
    }

    public String buildPatientLockKey(String patientId) {
        return "PATIENT_" + patientId;
    }

    public long getTimeoutByPatientType(String patientType) {
        if ("VIP".equalsIgnoreCase(patientType)) {
            return VIP_PATIENT_TIMEOUT_SECONDS;
        }
        return NORMAL_PATIENT_TIMEOUT_SECONDS;
    }

    public LockResult tryAcquire(String lockKey, String ownerId, Duration timeout) {
        LocalDateTime startTime = LocalDateTime.now();
        
        LockEntry existingEntry = locks.get(lockKey);
        if (existingEntry != null && existingEntry.isLocked()) {
            Duration waitTime = Duration.between(startTime, LocalDateTime.now());
            log.info("锁冲突 - 键: {}, 当前持有者: {}, 请求者: {}", lockKey, existingEntry.getOwner(), ownerId);
            return new LockResult(false, lockKey, "锁被其他进程持有", waitTime);
        }

        LockEntry newEntry = new LockEntry(ownerId, timeout);
        LockEntry previous = locks.putIfAbsent(lockKey, newEntry);
        
        if (previous != null && previous.isLocked()) {
            Duration waitTime = Duration.between(startTime, LocalDateTime.now());
            return new LockResult(false, lockKey, "锁被其他进程持有", waitTime);
        }

        Duration waitTime = Duration.between(startTime, LocalDateTime.now());
        log.info("锁获取成功 - 键: {}, 持有者: {}, 超时: {}秒", lockKey, ownerId, timeout.getSeconds());
        return new LockResult(true, lockKey, "锁获取成功", waitTime);
    }

    public LockResult tryAcquireForSchedule(String scheduleId, String patientId, String patientType) {
        String lockKey = buildScheduleLockKey(scheduleId);
        long timeoutSeconds = getTimeoutByPatientType(patientType);
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        return tryAcquire(lockKey, patientId, timeout);
    }

    public boolean release(String lockKey) {
        LockEntry entry = locks.remove(lockKey);
        if (entry != null) {
            entry.release();
            log.info("锁释放 - 键: {}", lockKey);
            return true;
        }
        return false;
    }

    public boolean releaseScheduleLock(String scheduleId) {
        return release(buildScheduleLockKey(scheduleId));
    }

    public boolean isLocked(String lockKey) {
        LockEntry entry = locks.get(lockKey);
        return entry != null && entry.isLocked();
    }

    public boolean isScheduleLocked(String scheduleId) {
        return isLocked(buildScheduleLockKey(scheduleId));
    }

    public void cleanExpiredLocks() {
        int cleaned = 0;
        for (Map.Entry<String, LockEntry> entry : locks.entrySet()) {
            if (entry.getValue().isExpired()) {
                locks.remove(entry.getKey());
                cleaned++;
                log.info("清理过期锁 - 键: {}", entry.getKey());
            }
        }
        if (cleaned > 0) {
            log.info("清理了 {} 个过期锁", cleaned);
        }
    }

    public int getActiveLockCount() {
        cleanExpiredLocks();
        return (int) locks.entrySet().stream()
                .filter(entry -> entry.getValue().isLocked())
                .count();
    }

    public void clearAllLocks() {
        locks.clear();
        log.info("已清除所有锁");
    }
}
