package com.schedulebook.service;

import com.schedulebook.config.LockTimeoutConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ScheduleLockService {
    
    private static final Logger logger = LoggerFactory.getLogger(ScheduleLockService.class);
    
    private final Map<String, LockHolder> lockMap = new ConcurrentHashMap<>();
    
    @Autowired
    private LockTimeoutConfig lockTimeoutConfig;
    
    private static class LockHolder {
        final ReentrantLock lock;
        final long createdAt;
        final long timeoutSeconds;
        final String bookingId;
        final String urgencyLevel;
        
        LockHolder(String bookingId, long timeoutSeconds, String urgencyLevel) {
            this.lock = new ReentrantLock();
            this.createdAt = System.currentTimeMillis();
            this.timeoutSeconds = timeoutSeconds;
            this.bookingId = bookingId;
            this.urgencyLevel = urgencyLevel;
        }
        
        boolean isExpired() {
            long elapsed = System.currentTimeMillis() - createdAt;
            return elapsed > timeoutSeconds * 1000;
        }
    }
    
    private String generateLockKey(String resourceId, LocalDate date, LocalTime time) {
        return resourceId + ":" + date.toString() + ":" + time.toString();
    }
    
    public long getTimeoutForUrgency(String urgencyLevel) {
        return lockTimeoutConfig.getTimeoutForUrgency(urgencyLevel);
    }
    
    public boolean tryLock(String resourceId, LocalDate date, LocalTime time, 
                           String bookingId, String urgencyLevel) {
        String lockKey = generateLockKey(resourceId, date, time);
        long timeoutSeconds = getTimeoutForUrgency(urgencyLevel);
        
        logger.debug("尝试获取锁定，资源ID: {}, 日期: {}, 时间: {}, 预约ID: {}, 紧急程度: {}, 超时: {}秒", 
                resourceId, date, time, bookingId, urgencyLevel, timeoutSeconds);
        
        LockHolder newHolder = new LockHolder(bookingId, timeoutSeconds, urgencyLevel);
        
        LockHolder existingHolder = lockMap.putIfAbsent(lockKey, newHolder);
        
        if (existingHolder != null) {
            if (existingHolder.isExpired()) {
                logger.debug("现有锁定已过期，尝试替换，原预约ID: {}, 原紧急程度: {}", 
                        existingHolder.bookingId, existingHolder.urgencyLevel);
                lockMap.replace(lockKey, existingHolder, newHolder);
                return true;
            }
            
            if (existingHolder.bookingId.equals(bookingId)) {
                logger.debug("同一预约已持有锁定，预约ID: {}", bookingId);
                return true;
            }
            
            logger.debug("锁定已被其他预约持有，预约ID: {}, 紧急程度: {}", 
                    existingHolder.bookingId, existingHolder.urgencyLevel);
            return false;
        }
        
        logger.debug("成功获取锁定，预约ID: {}, 紧急程度: {}, 超时: {}秒", 
                bookingId, urgencyLevel, timeoutSeconds);
        return true;
    }
    
    public boolean tryLock(String resourceId, LocalDate date, LocalTime time, String bookingId) {
        return tryLock(resourceId, date, time, bookingId, "normal");
    }
    
    public boolean tryLockUrgent(String resourceId, LocalDate date, LocalTime time, String bookingId) {
        return tryLock(resourceId, date, time, bookingId, "urgent");
    }
    
    public void releaseLock(String resourceId, LocalDate date, LocalTime time, String bookingId) {
        String lockKey = generateLockKey(resourceId, date, time);
        
        logger.debug("尝试释放锁定，资源ID: {}, 日期: {}, 时间: {}, 预约ID: {}", 
                resourceId, date, time, bookingId);
        
        LockHolder holder = lockMap.get(lockKey);
        
        if (holder != null && holder.bookingId.equals(bookingId)) {
            lockMap.remove(lockKey);
            logger.debug("成功释放锁定，预约ID: {}, 紧急程度: {}", bookingId, holder.urgencyLevel);
        } else if (holder != null) {
            logger.warn("尝试释放不是自己持有的锁定，持有预约ID: {}, 请求预约ID: {}", 
                    holder.bookingId, bookingId);
        }
    }
    
    public boolean isLocked(String resourceId, LocalDate date, LocalTime time) {
        String lockKey = generateLockKey(resourceId, date, time);
        LockHolder holder = lockMap.get(lockKey);
        
        if (holder == null) {
            return false;
        }
        
        if (holder.isExpired()) {
            lockMap.remove(lockKey);
            return false;
        }
        
        return true;
    }
    
    public String getLockHolder(String resourceId, LocalDate date, LocalTime time) {
        String lockKey = generateLockKey(resourceId, date, time);
        LockHolder holder = lockMap.get(lockKey);
        return holder != null ? holder.bookingId : null;
    }
    
    public String getLockUrgency(String resourceId, LocalDate date, LocalTime time) {
        String lockKey = generateLockKey(resourceId, date, time);
        LockHolder holder = lockMap.get(lockKey);
        return holder != null ? holder.urgencyLevel : null;
    }
    
    public long getLockRemainingTime(String resourceId, LocalDate date, LocalTime time) {
        String lockKey = generateLockKey(resourceId, date, time);
        LockHolder holder = lockMap.get(lockKey);
        
        if (holder == null) {
            return 0;
        }
        
        long elapsed = System.currentTimeMillis() - holder.createdAt;
        long remaining = (holder.timeoutSeconds * 1000) - elapsed;
        
        return Math.max(0, remaining);
    }
    
    public void clearAllLocks() {
        lockMap.clear();
        logger.info("已清除所有锁定");
    }
    
    public int getActiveLockCount() {
        cleanExpiredLocks();
        return lockMap.size();
    }
    
    private void cleanExpiredLocks() {
        lockMap.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
    
    public Map<String, Long> getAvailableUrgencyLevels() {
        return lockTimeoutConfig.getTimeoutSeconds();
    }
    
    public boolean isValidUrgencyLevel(String urgencyLevel) {
        return lockTimeoutConfig.isValidUrgencyLevel(urgencyLevel);
    }
    
    public void updateTimeoutConfig(String urgencyLevel, long timeoutSeconds) {
        if (lockTimeoutConfig.getTimeoutSeconds().containsKey(urgencyLevel)) {
            lockTimeoutConfig.getTimeoutSeconds().put(urgencyLevel, timeoutSeconds);
            logger.info("更新锁定超时配置，紧急程度: {}, 超时: {}秒", urgencyLevel, timeoutSeconds);
        }
    }
}
