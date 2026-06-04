package com.cicd.server.runner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedJobLock {

    private static final String LOCK_KEY_PREFIX = "cicd:lock:job:assign:";
    private static final long DEFAULT_LOCK_SECONDS = 30;
    private static final long DEFAULT_WAIT_MS = 5000;
    private static final long RETRY_INTERVAL_MS = 200;

    private final StringRedisTemplate redisTemplate;

    public boolean tryLock(Long jobId) {
        return tryLock(jobId, DEFAULT_WAIT_MS);
    }

    public boolean tryLock(Long jobId, long waitTimeoutMs) {
        String lockKey = LOCK_KEY_PREFIX + jobId;
        long deadline = System.currentTimeMillis() + waitTimeoutMs;

        while (System.currentTimeMillis() < deadline) {
            Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, String.valueOf(Thread.currentThread().getId()),
                    DEFAULT_LOCK_SECONDS, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(acquired)) {
                log.debug("Acquired lock for job {}", jobId);
                return true;
            }
            try {
                Thread.sleep(RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("Failed to acquire lock for job {} within {}ms", jobId, waitTimeoutMs);
        return false;
    }

    public void unlock(Long jobId) {
        String lockKey = LOCK_KEY_PREFIX + jobId;
        String currentValue = redisTemplate.opsForValue().get(lockKey);
        if (String.valueOf(Thread.currentThread().getId()).equals(currentValue)) {
            redisTemplate.delete(lockKey);
            log.debug("Released lock for job {}", jobId);
        }
    }
}
