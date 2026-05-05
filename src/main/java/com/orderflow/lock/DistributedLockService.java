package com.orderflow.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class DistributedLockService {

    private static final Logger logger = LoggerFactory.getLogger(DistributedLockService.class);

    private static final String LOCK_PREFIX = "order:lock:";
    private static final long DEFAULT_WAIT_TIME = 3000;
    private static final long DEFAULT_LEASE_TIME = 10000;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private final ThreadLocal<String> lockValueThreadLocal = new ThreadLocal<>();

    public boolean tryLock(String key) {
        return tryLock(key, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS);
    }

    public boolean tryLock(String key, long waitTime, long leaseTime, TimeUnit unit) {
        String lockKey = LOCK_PREFIX + key;
        String lockValue = UUID.randomUUID().toString();

        long startTime = System.currentTimeMillis();
        long waitMillis = unit.toMillis(waitTime);

        while (System.currentTimeMillis() - startTime < waitMillis) {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockValue, leaseTime, unit);

            if (Boolean.TRUE.equals(acquired)) {
                lockValueThreadLocal.set(lockValue);
                logger.debug("获取分布式锁成功，key: {}, value: {}", lockKey, lockValue);
                return true;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("获取锁时被中断，key: {}", lockKey);
                return false;
            }
        }

        logger.warn("获取分布式锁超时，key: {}", lockKey);
        return false;
    }

    public boolean tryLockNoWait(String key, long leaseTime, TimeUnit unit) {
        String lockKey = LOCK_PREFIX + key;
        String lockValue = UUID.randomUUID().toString();

        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, leaseTime, unit);

        if (Boolean.TRUE.equals(acquired)) {
            lockValueThreadLocal.set(lockValue);
            logger.debug("获取分布式锁成功（无等待），key: {}, value: {}", lockKey, lockValue);
            return true;
        }

        logger.debug("获取分布式锁失败（锁被占用），key: {}", lockKey);
        return false;
    }

    public void unlock(String key) {
        String lockKey = LOCK_PREFIX + key;
        String lockValue = lockValueThreadLocal.get();

        if (lockValue == null) {
            logger.warn("当前线程未持有锁，key: {}", lockKey);
            return;
        }

        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('del', KEYS[1]) " +
                        "else " +
                        "return 0 " +
                        "end";

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Long.class);

        Long result = redisTemplate.execute(redisScript, Collections.singletonList(lockKey), lockValue);

        if (result != null && result > 0) {
            logger.debug("释放分布式锁成功，key: {}, value: {}", lockKey, lockValue);
        } else {
            logger.warn("释放分布式锁失败，可能锁已过期或被其他线程释放，key: {}, value: {}", lockKey, lockValue);
        }

        lockValueThreadLocal.remove();
    }

    public <T> T executeWithLock(String key, LockExecutor<T> executor) {
        return executeWithLock(key, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.MILLISECONDS, executor);
    }

    public <T> T executeWithLock(String key, long waitTime, long leaseTime, TimeUnit unit, LockExecutor<T> executor) {
        boolean locked = tryLock(key, waitTime, leaseTime, unit);
        if (!locked) {
            throw new IllegalStateException("获取分布式锁失败: " + key);
        }

        try {
            return executor.execute();
        } finally {
            unlock(key);
        }
    }

    @FunctionalInterface
    public interface LockExecutor<T> {
        T execute();
    }
}
