package com.adplatform.lock;

import com.adplatform.config.AdPlatformConfig;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class DistributedLockService {
    private static final Logger logger = LoggerFactory.getLogger(DistributedLockService.class);
    private final RedissonClient redissonClient;
    private final AdPlatformConfig config;

    public DistributedLockService(RedissonClient redissonClient, AdPlatformConfig config) {
        this.redissonClient = redissonClient;
        this.config = config;
    }

    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime, Supplier<T> supplier) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            boolean acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
            if (!acquired) {
                logger.warn("获取锁失败: {}, waitTime={}s, leaseTime={}s", lockKey, waitTime, leaseTime);
                throw new RuntimeException("获取分布式锁失败，请稍后重试");
            }
            try {
                return supplier.get();
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    logger.debug("锁已释放: {}", lockKey);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("获取锁被中断: {}", lockKey, e);
            throw new RuntimeException("获取锁被中断", e);
        }
    }

    public <T> T executeWithLock(String lockKey, String emergencyLevel, Supplier<T> supplier) {
        AdPlatformConfig.TimeoutLevel timeout = getTimeoutLevel(emergencyLevel);
        return executeWithLock(lockKey, timeout.getWaitTime(), timeout.getLeaseTime(), supplier);
    }

    public <T> T executeWithLock(String lockKey, Supplier<T> supplier) {
        AdPlatformConfig.LockTimeoutConfig lockConfig = config.getLock();
        return executeWithLock(lockKey, lockConfig.getDefaultWaitTime(), lockConfig.getDefaultLeaseTime(), supplier);
    }

    public AdPlatformConfig.TimeoutLevel getTimeoutLevel(String emergencyLevel) {
        if (emergencyLevel == null || emergencyLevel.isEmpty()) {
            emergencyLevel = "normal";
        }
        
        AdPlatformConfig.TimeoutLevel timeout = config.getLock().getLevels().get(emergencyLevel.toLowerCase());
        if (timeout == null) {
            logger.warn("未找到紧急程度对应的锁超时配置: {}, 使用默认配置", emergencyLevel);
            timeout = new AdPlatformConfig.TimeoutLevel(
                config.getLock().getDefaultWaitTime(),
                config.getLock().getDefaultLeaseTime()
            );
        }
        return timeout;
    }

    public long getWaitTime(String emergencyLevel) {
        return getTimeoutLevel(emergencyLevel).getWaitTime();
    }

    public long getLeaseTime(String emergencyLevel) {
        return getTimeoutLevel(emergencyLevel).getLeaseTime();
    }
}
