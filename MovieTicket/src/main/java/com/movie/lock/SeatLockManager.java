package com.movie.lock;

import com.movie.entity.User;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class SeatLockManager {

    public static final int LOCK_TIMEOUT_SECONDS_NORMAL = 300;
    public static final int LOCK_TIMEOUT_SECONDS_VIP = 120;
    public static final String LEVEL_VIP = "vip";
    public static final String LEVEL_NORMAL = "normal";

    private final Map<String, SeatLockEntry> seatLocks = new ConcurrentHashMap<>();
    private final ReentrantLock globalLock = new ReentrantLock();

    public static class SeatLockEntry {
        private final ReentrantLock lock = new ReentrantLock(true);
        private final Condition lockReleased = lock.newCondition();
        private String currentHolder;
        private LocalDateTime lockTime;
        private int lockTimeoutSeconds;
        private volatile boolean isLocked;

        public ReentrantLock getLock() {
            return lock;
        }

        public Condition getLockReleased() {
            return lockReleased;
        }

        public String getCurrentHolder() {
            return currentHolder;
        }

        public void setCurrentHolder(String currentHolder) {
            this.currentHolder = currentHolder;
        }

        public LocalDateTime getLockTime() {
            return lockTime;
        }

        public void setLockTime(LocalDateTime lockTime) {
            this.lockTime = lockTime;
        }

        public int getLockTimeoutSeconds() {
            return lockTimeoutSeconds;
        }

        public void setLockTimeoutSeconds(int lockTimeoutSeconds) {
            this.lockTimeoutSeconds = lockTimeoutSeconds;
        }

        public boolean isLocked() {
            return isLocked;
        }

        public void setLocked(boolean locked) {
            isLocked = locked;
        }

        public boolean isExpired(LocalDateTime now) {
            if (lockTime == null || !isLocked) {
                return false;
            }
            return lockTime.plusSeconds(lockTimeoutSeconds).isBefore(now);
        }
    }

    public int getLockTimeoutSeconds(User user) {
        if (user != null && LEVEL_VIP.equalsIgnoreCase(user.getUserLevel())) {
            return LOCK_TIMEOUT_SECONDS_VIP;
        }
        return LOCK_TIMEOUT_SECONDS_NORMAL;
    }

    public int getLockTimeoutSeconds(String userLevel) {
        if (LEVEL_VIP.equalsIgnoreCase(userLevel)) {
            return LOCK_TIMEOUT_SECONDS_VIP;
        }
        return LOCK_TIMEOUT_SECONDS_NORMAL;
    }

    public boolean tryAcquireLock(String seatId, String userId, User user) {
        return tryAcquireLock(seatId, userId, getLockTimeoutSeconds(user));
    }

    public boolean tryAcquireLock(String seatId, String userId, int timeoutSeconds) {
        globalLock.lock();
        try {
            SeatLockEntry entry = seatLocks.computeIfAbsent(seatId, k -> new SeatLockEntry());

            if (entry.isLocked && userId.equals(entry.getCurrentHolder())) {
                return true;
            }

            if (entry.isLocked && !entry.isExpired(LocalDateTime.now())) {
                return false;
            }

            if (entry.isLocked && entry.isExpired(LocalDateTime.now())) {
                entry.setLocked(false);
                entry.setCurrentHolder(null);
                entry.setLockTime(null);
            }

            entry.setLocked(true);
            entry.setCurrentHolder(userId);
            entry.setLockTime(LocalDateTime.now());
            entry.setLockTimeoutSeconds(timeoutSeconds);
            return true;
        } finally {
            globalLock.unlock();
        }
    }

    public LockResult acquireLockWithWait(String seatId, String userId, User user, long waitMillis) {
        int timeoutSeconds = getLockTimeoutSeconds(user);
        globalLock.lock();
        try {
            SeatLockEntry entry = seatLocks.computeIfAbsent(seatId, k -> new SeatLockEntry());

            if (entry.isLocked && userId.equals(entry.getCurrentHolder())) {
                return new LockResult(true, null);
            }

            long startTime = System.currentTimeMillis();
            while (entry.isLocked && !entry.isExpired(LocalDateTime.now())) {
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed >= waitMillis) {
                    return new LockResult(false, "等待超时，座位已被其他用户锁定");
                }
                try {
                    entry.getLockReleased().await(waitMillis - elapsed, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new LockResult(false, "等待被中断");
                }
            }

            entry.setLocked(true);
            entry.setCurrentHolder(userId);
            entry.setLockTime(LocalDateTime.now());
            entry.setLockTimeoutSeconds(timeoutSeconds);
            return new LockResult(true, null);
        } finally {
            globalLock.unlock();
        }
    }

    public boolean releaseLock(String seatId, String userId) {
        globalLock.lock();
        try {
            SeatLockEntry entry = seatLocks.get(seatId);
            if (entry == null) {
                return false;
            }

            if (!entry.isLocked) {
                return false;
            }

            if (userId != null && !userId.equals(entry.getCurrentHolder())) {
                return false;
            }

            entry.setLocked(false);
            entry.setCurrentHolder(null);
            entry.setLockTime(null);
            entry.getLockReleased().signalAll();
            return true;
        } finally {
            globalLock.unlock();
        }
    }

    public boolean isLockAvailable(String seatId) {
        globalLock.lock();
        try {
            SeatLockEntry entry = seatLocks.get(seatId);
            if (entry == null) {
                return true;
            }
            if (!entry.isLocked) {
                return true;
            }
            return entry.isExpired(LocalDateTime.now());
        } finally {
            globalLock.unlock();
        }
    }

    public String getLockHolder(String seatId) {
        globalLock.lock();
        try {
            SeatLockEntry entry = seatLocks.get(seatId);
            if (entry == null || !entry.isLocked) {
                return null;
            }
            if (entry.isExpired(LocalDateTime.now())) {
                return null;
            }
            return entry.getCurrentHolder();
        } finally {
            globalLock.unlock();
        }
    }

    public boolean isLockExpired(String seatId) {
        globalLock.lock();
        try {
            SeatLockEntry entry = seatLocks.get(seatId);
            if (entry == null || !entry.isLocked) {
                return true;
            }
            return entry.isExpired(LocalDateTime.now());
        } finally {
            globalLock.unlock();
        }
    }

    public void releaseExpiredLocks() {
        globalLock.lock();
        try {
            LocalDateTime now = LocalDateTime.now();
            for (Map.Entry<String, SeatLockEntry> mapEntry : seatLocks.entrySet()) {
                SeatLockEntry entry = mapEntry.getValue();
                if (entry.isLocked && entry.isExpired(now)) {
                    entry.setLocked(false);
                    entry.setCurrentHolder(null);
                    entry.setLockTime(null);
                    entry.getLockReleased().signalAll();
                }
            }
        } finally {
            globalLock.unlock();
        }
    }

    public int getLockedSeatCount() {
        int count = 0;
        globalLock.lock();
        try {
            for (SeatLockEntry entry : seatLocks.values()) {
                if (entry.isLocked && !entry.isExpired(LocalDateTime.now())) {
                    count++;
                }
            }
        } finally {
            globalLock.unlock();
        }
        return count;
    }

    public static class LockResult {
        private final boolean success;
        private final String message;

        public LockResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }
}
