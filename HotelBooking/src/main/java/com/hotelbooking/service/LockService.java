package com.hotelbooking.service;

import com.hotelbooking.config.LockTimeoutConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class LockService {
    private static final Logger logger = LoggerFactory.getLogger(LockService.class);

    private final Map<String, LockHolder> lockMap = new ConcurrentHashMap<>();
    private final LockTimeoutConfig lockTimeoutConfig;

    public LockService(LockTimeoutConfig lockTimeoutConfig) {
        this.lockTimeoutConfig = lockTimeoutConfig;
    }

    public enum CustomerLevel {
        VIP("VIP"),
        GOLD("GOLD"),
        PLATINUM("PLATINUM"),
        NORMAL("NORMAL");

        private final String code;

        CustomerLevel(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }

        public static CustomerLevel fromCode(String code) {
            if (code == null) {
                return NORMAL;
            }
            for (CustomerLevel level : values()) {
                if (level.code.equalsIgnoreCase(code)) {
                    return level;
                }
            }
            return NORMAL;
        }
    }

    private static class LockHolder {
        private final String owner;
        private final long expireTime;
        private final AtomicBoolean released = new AtomicBoolean(false);
        private final String customerLevel;

        public LockHolder(String owner, long timeoutMillis, String customerLevel) {
            this.owner = owner;
            this.expireTime = System.currentTimeMillis() + timeoutMillis;
            this.customerLevel = customerLevel;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expireTime || released.get();
        }

        public String getOwner() {
            return owner;
        }

        public String getCustomerLevel() {
            return customerLevel;
        }

        public void release() {
            released.set(true);
        }
    }

    public boolean tryLock(String lockKey, String owner, String customerLevel) {
        long timeoutMillis = lockTimeoutConfig.getTimeoutMillis(customerLevel);
        return tryLockWithTimeout(lockKey, owner, customerLevel, timeoutMillis);
    }

    public boolean tryLock(String lockKey, String owner, CustomerLevel level) {
        return tryLock(lockKey, owner, level.getCode());
    }

    private boolean tryLockWithTimeout(String lockKey, String owner, String customerLevel, long timeoutMillis) {
        long startTime = System.currentTimeMillis();

        logger.debug("尝试获取锁: key={}, owner={}, level={}, timeout={}ms", 
                lockKey, owner, customerLevel, timeoutMillis);

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            LockHolder existingLock = lockMap.get(lockKey);
            
            if (existingLock == null || existingLock.isExpired()) {
                LockHolder newLock = new LockHolder(owner, timeoutMillis, customerLevel);
                LockHolder previous = lockMap.putIfAbsent(lockKey, newLock);
                
                if (previous == null || previous.isExpired()) {
                    if (previous != null) {
                        lockMap.replace(lockKey, previous, newLock);
                    }
                    logger.info("锁获取成功: key={}, owner={}, level={}, 耗时={}ms", 
                            lockKey, owner, customerLevel, 
                            System.currentTimeMillis() - startTime);
                    return true;
                }
            }
            
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("锁获取被中断: key={}, owner={}", lockKey, owner);
                return false;
            }
        }
        
        logger.warn("锁获取超时: key={}, owner={}, level={}, 等待时间={}ms", 
                lockKey, owner, customerLevel, timeoutMillis);
        return false;
    }

    public void releaseLock(String lockKey, String owner) {
        LockHolder lock = lockMap.get(lockKey);
        if (lock != null && lock.getOwner().equals(owner)) {
            lock.release();
            lockMap.remove(lockKey, lock);
            logger.info("锁释放成功: key={}, owner={}, level={}", 
                    lockKey, owner, lock.getCustomerLevel());
        } else {
            logger.warn("锁释放失败: key={}, owner={}, 锁不存在或非当前owner持有", lockKey, owner);
        }
    }

    public boolean isLocked(String lockKey) {
        LockHolder lock = lockMap.get(lockKey);
        return lock != null && !lock.isExpired();
    }

    public String getLockOwner(String lockKey) {
        LockHolder lock = lockMap.get(lockKey);
        return (lock != null && !lock.isExpired()) ? lock.getOwner() : null;
    }

    public String getLockCustomerLevel(String lockKey) {
        LockHolder lock = lockMap.get(lockKey);
        return (lock != null && !lock.isExpired()) ? lock.getCustomerLevel() : null;
    }

    public long getConfiguredTimeout(String customerLevel) {
        return lockTimeoutConfig.getTimeoutMillis(customerLevel);
    }

    public long getConfiguredTimeout(CustomerLevel level) {
        return lockTimeoutConfig.getTimeoutMillis(level.getCode());
    }

    public Map<String, LockTimeoutConfig.LockTimeoutConfigEntry> getAllTimeoutConfigs() {
        return lockTimeoutConfig.getTimeouts();
    }

    public String getDefaultCustomerLevel() {
        return lockTimeoutConfig.getDefaultLevel();
    }
}
