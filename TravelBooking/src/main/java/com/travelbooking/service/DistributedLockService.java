package com.travelbooking.service;

import com.travelbooking.config.BookingLockConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
public class DistributedLockService {

    private final BookingLockConfig lockConfig;

    private final Map<String, LockHolder> locks = new ConcurrentHashMap<>();

    public enum BookingUrgency {
        EMERGENCY,
        NORMAL;

        public static BookingUrgency fromString(String value) {
            if (value == null) return NORMAL;
            try {
                return BookingUrgency.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                return NORMAL;
            }
        }
    }

    private static class LockHolder {
        final ReentrantLock lock = new ReentrantLock();
        volatile String holderId;
        volatile long acquireTime;
        volatile String urgency;
    }

    public boolean acquireLock(String routeId, String bookingId, BookingUrgency urgency) {
        String lockKey = "route:" + routeId;
        LockHolder holder = locks.computeIfAbsent(lockKey, k -> new LockHolder());

        long timeout = getTimeout(urgency);
        TimeUnit unit = lockConfig.getDefaultTimeUnit();

        log.debug("尝试获取锁 - 线路ID: {}, 预订ID: {}, 紧急程度: {}, 超时: {} {}", 
                routeId, bookingId, urgency, timeout, unit);

        try {
            boolean acquired = holder.lock.tryLock(timeout, unit);
            if (acquired) {
                holder.holderId = bookingId;
                holder.acquireTime = System.currentTimeMillis();
                holder.urgency = urgency.name();
                log.debug("获取锁成功 - 线路ID: {}, 预订ID: {}", routeId, bookingId);
                return true;
            }
            log.warn("获取锁超时 - 线路ID: {}, 预订ID: {}, 等待: {} {}", routeId, bookingId, timeout, unit);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取锁被中断 - 线路ID: {}, 预订ID: {}", routeId, bookingId, e);
            return false;
        }
    }

    public boolean acquireLock(String routeId, String bookingId, String urgencyString) {
        return acquireLock(routeId, bookingId, BookingUrgency.fromString(urgencyString));
    }

    public long getTimeout(BookingUrgency urgency) {
        return urgency == BookingUrgency.EMERGENCY 
                ? lockConfig.getEmergencyTimeoutSeconds() 
                : lockConfig.getNormalTimeoutSeconds();
    }

    public long getTimeoutMillis(BookingUrgency urgency) {
        return TimeUnit.SECONDS.toMillis(getTimeout(urgency));
    }

    public void releaseLock(String routeId, String bookingId) {
        String lockKey = "route:" + routeId;
        LockHolder holder = locks.get(lockKey);
        if (holder != null && bookingId.equals(holder.holderId)) {
            holder.holderId = null;
            holder.lock.unlock();
            log.debug("释放锁成功 - 线路ID: {}, 预订ID: {}", routeId, bookingId);
        }
    }

    public boolean isLocked(String routeId) {
        String lockKey = "route:" + routeId;
        LockHolder holder = locks.get(lockKey);
        return holder != null && holder.lock.isLocked();
    }

    public String getLockHolder(String routeId) {
        String lockKey = "route:" + routeId;
        LockHolder holder = locks.get(lockKey);
        return holder != null ? holder.holderId : null;
    }

    public String getLockUrgency(String routeId) {
        String lockKey = "route:" + routeId;
        LockHolder holder = locks.get(lockKey);
        return holder != null ? holder.urgency : null;
    }

    public void forceClearLock(String routeId) {
        String lockKey = "route:" + routeId;
        LockHolder holder = locks.remove(lockKey);
        if (holder != null && holder.lock.isLocked()) {
            holder.lock.unlock();
            log.warn("强制清除锁 - 线路ID: {}", routeId);
        }
    }

    public BookingLockConfig.LockTimeoutConfig getConfigForUrgency(BookingUrgency urgency) {
        return lockConfig.getConfigByUrgency(urgency.name());
    }

    public Map<String, Integer> getActiveLocksCount() {
        int total = locks.size();
        int locked = (int) locks.values().stream().filter(h -> h.lock.isLocked()).count();
        return Map.of(
            "total", total,
            "locked", locked
        );
    }
}
