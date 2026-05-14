package com.logistics.service;

import com.logistics.config.CourierLockConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class CourierLockService {

    private final CourierLockConfig courierLockConfig;

    private final Map<String, CourierLock> locks = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> distributedLocks = new ConcurrentHashMap<>();

    public static final String URGENCY_NORMAL = "NORMAL";
    public static final String URGENCY_URGENT = "URGENT";
    public static final String URGENCY_SUPER_URGENT = "SUPER_URGENT";

    public static final long URGENCY_NORMAL_TIMEOUT_SECONDS = 30;
    public static final long URGENCY_URGENT_TIMEOUT_SECONDS = 5;
    public static final long URGENCY_SUPER_URGENT_TIMEOUT_SECONDS = 2;

    public boolean tryLock(String courierId, String logisticsId, String urgency) {
        long timeoutSeconds = getTimeoutByUrgency(urgency);
        
        ReentrantLock lock = distributedLocks.computeIfAbsent(courierId, k -> new ReentrantLock());
        
        try {
            boolean acquired = lock.tryLock(timeoutSeconds, TimeUnit.SECONDS);
            if (acquired) {
                CourierLock existing = locks.get(courierId);
                if (existing != null && !isExpired(existing)) {
                    lock.unlock();
                    log.warn("配送员 {} 已被锁定，当前任务：{}", courierId, existing.getLogisticsId());
                    return false;
                }
                
                CourierLock newLock = new CourierLock(courierId, logisticsId, urgency, 
                        LocalDateTime.now().plusSeconds(timeoutSeconds));
                locks.put(courierId, newLock);
                log.info("配送员 {} 锁定成功，任务：{}，紧急程度：{}，超时时间：{}秒", 
                        courierId, logisticsId, urgency, timeoutSeconds);
                return true;
            }
            log.warn("获取配送员 {} 锁超时（{}秒）", courierId, timeoutSeconds);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取配送员 {} 锁被中断", courierId, e);
            return false;
        }
    }

    public void releaseLock(String courierId, String logisticsId) {
        CourierLock lock = locks.get(courierId);
        if (lock != null && lock.getLogisticsId().equals(logisticsId)) {
            locks.remove(courierId);
            ReentrantLock distributedLock = distributedLocks.get(courierId);
            if (distributedLock != null && distributedLock.isHeldByCurrentThread()) {
                distributedLock.unlock();
            }
            log.info("配送员 {} 锁已释放，任务：{}", courierId, logisticsId);
        } else {
            log.warn("配送员 {} 锁不存在或不属于当前任务 {}", courierId, logisticsId);
        }
    }

    public boolean isLocked(String courierId) {
        CourierLock lock = locks.get(courierId);
        return lock != null && !isExpired(lock);
    }

    public CourierLock getLockInfo(String courierId) {
        return locks.get(courierId);
    }

    private boolean isExpired(CourierLock lock) {
        return LocalDateTime.now().isAfter(lock.getExpireTime());
    }

    private long getTimeoutByUrgency(String urgency) {
        if (urgency == null) {
            return courierLockConfig.getTimeoutSeconds(URGENCY_NORMAL);
        }
        long configTimeout = courierLockConfig.getTimeoutSeconds(urgency);
        return configTimeout > 0 ? configTimeout : getDefaultTimeout(urgency);
    }

    private long getDefaultTimeout(String urgency) {
        return switch (urgency) {
            case URGENCY_SUPER_URGENT -> URGENCY_SUPER_URGENT_TIMEOUT_SECONDS;
            case URGENCY_URGENT -> URGENCY_URGENT_TIMEOUT_SECONDS;
            default -> URGENCY_NORMAL_TIMEOUT_SECONDS;
        };
    }

    public boolean tryLockWithRetry(String courierId, String logisticsId, String urgency, int maxRetries) {
        for (int i = 0; i < maxRetries; i++) {
            if (tryLock(courierId, logisticsId, urgency)) {
                return true;
            }
            try {
                Thread.sleep(100 * (i + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public Map<String, Long> getAllTimeoutConfigs() {
        return courierLockConfig.getAllTimeouts();
    }

    public void updateTimeoutConfig(String urgency, long timeoutSeconds) {
        courierLockConfig.updateTimeout(urgency, timeoutSeconds);
        log.info("更新锁定超时配置: {} -> {}秒", urgency, timeoutSeconds);
    }

    public static class CourierLock {
        private final String courierId;
        private final String logisticsId;
        private final String urgency;
        private final LocalDateTime expireTime;

        public CourierLock(String courierId, String logisticsId, String urgency, LocalDateTime expireTime) {
            this.courierId = courierId;
            this.logisticsId = logisticsId;
            this.urgency = urgency;
            this.expireTime = expireTime;
        }

        public String getCourierId() {
            return courierId;
        }

        public String getLogisticsId() {
            return logisticsId;
        }

        public String getUrgency() {
            return urgency;
        }

        public LocalDateTime getExpireTime() {
            return expireTime;
        }
    }
}
