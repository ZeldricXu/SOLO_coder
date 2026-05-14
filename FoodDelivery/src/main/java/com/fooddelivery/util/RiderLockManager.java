package com.fooddelivery.util;

import com.fooddelivery.config.LockConfigProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class RiderLockManager {

    @Autowired
    private LockConfigProperties lockConfig;

    private final Map<String, RiderLock> locks = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> mutexLocks = new ConcurrentHashMap<>();

    public enum LockType {
        URGENCY_ORDER(3, TimeUnit.SECONDS),
        NORMAL_ORDER(10, TimeUnit.SECONDS),
        SLOW_ORDER(30, TimeUnit.SECONDS);

        private final long timeout;
        private final TimeUnit unit;

        LockType(long timeout, TimeUnit unit) {
            this.timeout = timeout;
            this.unit = unit;
        }

        public long getTimeout() {
            return timeout;
        }

        public TimeUnit getUnit() {
            return unit;
        }
    }

    public static class RiderLock {
        private String riderId;
        private String orderId;
        private String urgency;
        private long acquireTime;
        private long expireTime;
        private long timeout;
        private TimeUnit unit;

        public RiderLock(String riderId, String orderId, String urgency, long timeout, TimeUnit unit) {
            this.riderId = riderId;
            this.orderId = orderId;
            this.urgency = urgency;
            this.acquireTime = System.currentTimeMillis();
            this.timeout = timeout;
            this.unit = unit;
            this.expireTime = acquireTime + unit.toMillis(timeout);
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expireTime;
        }

        public String getRiderId() {
            return riderId;
        }

        public String getOrderId() {
            return orderId;
        }

        public String getUrgency() {
            return urgency;
        }

        public long getTimeout() {
            return timeout;
        }

        public TimeUnit getUnit() {
            return unit;
        }

        public long getExpireTime() {
            return expireTime;
        }
    }

    @PostConstruct
    public void init() {
        if (lockConfig.getTimeouts().isEmpty()) {
            LockConfigProperties.LockTimeoutConfig urgencyConfig = new LockConfigProperties.LockTimeoutConfig();
            urgencyConfig.setTimeout(3);
            urgencyConfig.setUnit(TimeUnit.SECONDS);
            urgencyConfig.setDescription("紧急订单");
            lockConfig.getTimeouts().put("urgency", urgencyConfig);

            LockConfigProperties.LockTimeoutConfig normalConfig = new LockConfigProperties.LockTimeoutConfig();
            normalConfig.setTimeout(10);
            normalConfig.setUnit(TimeUnit.SECONDS);
            normalConfig.setDescription("普通订单");
            lockConfig.getTimeouts().put("normal", normalConfig);

            LockConfigProperties.LockTimeoutConfig slowConfig = new LockConfigProperties.LockTimeoutConfig();
            slowConfig.setTimeout(30);
            slowConfig.setUnit(TimeUnit.SECONDS);
            slowConfig.setDescription("慢单");
            lockConfig.getTimeouts().put("slow", slowConfig);
        }
    }

    public RiderLock tryLock(String riderId, String orderId, String urgency) {
        LockConfigProperties.LockTimeoutConfig config = lockConfig.getTimeoutConfig(urgency);
        return tryLockInternal(riderId, orderId, urgency, config.getTimeout(), config.getUnit());
    }

    public RiderLock tryLock(String riderId, String orderId, LockType lockType) {
        return tryLockInternal(riderId, orderId, lockType.name().toLowerCase(), lockType.getTimeout(), lockType.getUnit());
    }

    private RiderLock tryLockInternal(String riderId, String orderId, String urgency, long timeout, TimeUnit unit) {
        ReentrantLock mutex = mutexLocks.computeIfAbsent(riderId, k -> new ReentrantLock());
        try {
            if (!mutex.tryLock(1, TimeUnit.SECONDS)) {
                return null;
            }
            try {
                RiderLock existingLock = locks.get(riderId);
                if (existingLock != null && !existingLock.isExpired()) {
                    return null;
                }
                RiderLock newLock = new RiderLock(riderId, orderId, urgency, timeout, unit);
                locks.put(riderId, newLock);
                return newLock;
            } finally {
                mutex.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public boolean releaseLock(String riderId, String orderId) {
        ReentrantLock mutex = mutexLocks.computeIfAbsent(riderId, k -> new ReentrantLock());
        try {
            if (!mutex.tryLock(1, TimeUnit.SECONDS)) {
                return false;
            }
            try {
                RiderLock existingLock = locks.get(riderId);
                if (existingLock != null && existingLock.getOrderId().equals(orderId)) {
                    locks.remove(riderId);
                    return true;
                }
                return false;
            } finally {
                mutex.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public RiderLock getLock(String riderId) {
        RiderLock lock = locks.get(riderId);
        if (lock != null && lock.isExpired()) {
            locks.remove(riderId);
            return null;
        }
        return lock;
    }

    public boolean isLocked(String riderId) {
        return getLock(riderId) != null;
    }

    public void clearAllLocks() {
        locks.clear();
    }

    public long getTimeoutForUrgency(String urgency) {
        LockConfigProperties.LockTimeoutConfig config = lockConfig.getTimeoutConfig(urgency);
        return config.getTimeout();
    }

    public TimeUnit getTimeUnitForUrgency(String urgency) {
        LockConfigProperties.LockTimeoutConfig config = lockConfig.getTimeoutConfig(urgency);
        return config.getUnit();
    }

    public boolean isValidUrgency(String urgency) {
        return lockConfig.isValidUrgency(urgency);
    }
}
