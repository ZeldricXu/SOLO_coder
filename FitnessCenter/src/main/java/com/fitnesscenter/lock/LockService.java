package com.fitnesscenter.lock;

import com.fitnesscenter.config.LockConfig;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LockService {

    private final DistributedLock lock;
    private final LockConfig lockConfig;
    private final AtomicInteger lockAcquireFailures = new AtomicInteger(0);
    private final AtomicInteger lockAcquireSuccesses = new AtomicInteger(0);

    public LockService(DistributedLock lock, LockConfig lockConfig) {
        this.lock = lock;
        this.lockConfig = lockConfig;
    }

    public boolean tryLockCourseSlot(String courseId, String memberLevel) {
        String lockKey = "course_slot_" + courseId;
        boolean acquired = lock.tryAcquireLock(lockKey, memberLevel);
        if (acquired) {
            lockAcquireSuccesses.incrementAndGet();
        } else {
            lockAcquireFailures.incrementAndGet();
        }
        return acquired;
    }

    public boolean tryLockCourseSlot(String courseId, long timeout, TimeUnit unit) {
        String lockKey = "course_slot_" + courseId;
        boolean acquired = lock.tryAcquireLock(lockKey, timeout, unit);
        if (acquired) {
            lockAcquireSuccesses.incrementAndGet();
        } else {
            lockAcquireFailures.incrementAndGet();
        }
        return acquired;
    }

    public boolean releaseCourseSlotLock(String courseId) {
        String lockKey = "course_slot_" + courseId;
        return lock.releaseLock(lockKey);
    }

    public boolean isCourseSlotLocked(String courseId) {
        String lockKey = "course_slot_" + courseId;
        return lock.isLocked(lockKey);
    }

    public long getLockTimeout(String memberLevel) {
        return lockConfig.getTimeoutByLevel(memberLevel);
    }

    public void clearAllLocks() {
        lock.clearAllLocks();
        lockAcquireSuccesses.set(0);
        lockAcquireFailures.set(0);
    }

    public int getActiveLockCount() {
        return lock.getActiveLockCount();
    }

    public int getLockAcquireFailures() {
        return lockAcquireFailures.get();
    }

    public int getLockAcquireSuccesses() {
        return lockAcquireSuccesses.get();
    }

    public void resetStats() {
        lockAcquireSuccesses.set(0);
        lockAcquireFailures.set(0);
    }

    public LockConfig getLockConfig() {
        return lockConfig;
    }
}
