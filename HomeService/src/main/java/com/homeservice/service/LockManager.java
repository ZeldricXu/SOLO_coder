package com.homeservice.service;

import com.homeservice.config.CustomerLevelConfig;
import com.homeservice.config.CustomerLevelConfig.LockTimeoutConfig;
import com.homeservice.entity.Customer;
import com.homeservice.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LockManager {

    private static final Logger logger = LoggerFactory.getLogger(LockManager.class);

    @Autowired
    private CustomerLevelConfig customerLevelConfig;

    @Autowired
    private CustomerRepository customerRepository;

    private final Map<String, LockInfo> locks = new ConcurrentHashMap<>();

    public static class LockInfo {
        private final String lockOwner;
        private final String customerLevel;
        private final Instant acquiredAt;
        private final long holdTimeoutMs;
        private final long waitTimeoutMs;

        public LockInfo(String lockOwner, String customerLevel, long holdTimeoutMs, long waitTimeoutMs) {
            this.lockOwner = lockOwner;
            this.customerLevel = customerLevel;
            this.acquiredAt = Instant.now();
            this.holdTimeoutMs = holdTimeoutMs;
            this.waitTimeoutMs = waitTimeoutMs;
        }

        public String getLockOwner() { return lockOwner; }
        public String getCustomerLevel() { return customerLevel; }
        public Instant getAcquiredAt() { return acquiredAt; }
        public long getHoldTimeoutMs() { return holdTimeoutMs; }
        public long getWaitTimeoutMs() { return waitTimeoutMs; }

        public boolean isExpired() {
            return Instant.now().isAfter(acquiredAt.plusMillis(holdTimeoutMs));
        }

        public long getRemainingMs() {
            long remaining = holdTimeoutMs - java.time.Duration.between(acquiredAt, Instant.now()).toMillis();
            return Math.max(0, remaining);
        }
    }

    public enum CustomerType {
        VIP(3000),
        NORMAL(30000);

        private final long timeoutMs;

        CustomerType(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }
    }

    public boolean acquireLock(String staffId, String customerId) {
        LockTimeoutConfig timeoutConfig = getTimeoutConfigForCustomer(customerId);
        return acquireLockWithTimeout(staffId, customerId, 
            timeoutConfig.getHoldTimeoutMs(), timeoutConfig.getWaitTimeoutMs());
    }

    public boolean acquireLock(String staffId, String customerId, CustomerType customerType) {
        return acquireLock(staffId, customerId, customerType.getTimeoutMs());
    }

    public boolean acquireLock(String staffId, String customerId, long holdTimeoutMs) {
        long waitTimeoutMs = holdTimeoutMs / 6;
        return acquireLockWithTimeout(staffId, customerId, holdTimeoutMs, waitTimeoutMs);
    }

    public boolean acquireLockWithConfig(String staffId, String customerId, String customerLevelCode) {
        LockTimeoutConfig timeoutConfig = customerLevelConfig.getLockTimeoutByLevel(customerLevelCode);
        return acquireLockWithTimeout(staffId, customerId, 
            timeoutConfig.getHoldTimeoutMs(), timeoutConfig.getWaitTimeoutMs());
    }

    private boolean acquireLockWithTimeout(String staffId, String customerId, 
                                           long holdTimeoutMs, long waitTimeoutMs) {
        String lockKey = "lock:" + staffId;
        String customerLevel = getCustomerLevel(customerId);
        
        LockInfo currentLock = locks.get(lockKey);

        if (currentLock != null && !currentLock.isExpired()) {
            if (currentLock.getLockOwner().equals(customerId)) {
                logger.debug("Customer {} already holds lock for staff {}", customerId, staffId);
                return true;
            }
            logger.warn("Lock conflict for staff {}: owned by {}, requested by {}", 
                staffId, currentLock.getLockOwner(), customerId);
            return false;
        }

        String levelForLock = customerLevel != null ? customerLevel : "DEFAULT";
        LockInfo newLock = new LockInfo(customerId, levelForLock, holdTimeoutMs, waitTimeoutMs);
        locks.put(lockKey, newLock);
        
        logger.info("Lock acquired for staff {} by customer {} (level: {}, hold: {}ms, wait: {}ms)", 
            staffId, customerId, levelForLock, holdTimeoutMs, waitTimeoutMs);
        
        return true;
    }

    private LockTimeoutConfig getTimeoutConfigForCustomer(String customerId) {
        String customerLevel = getCustomerLevel(customerId);
        return customerLevelConfig.getLockTimeoutByLevel(customerLevel);
    }

    private String getCustomerLevel(String customerId) {
        try {
            Optional<Customer> customerOpt = customerRepository.findByCustomerId(customerId);
            if (customerOpt.isPresent() && customerOpt.get().getCustomerLevel() != null) {
                return customerOpt.get().getCustomerLevel().getCode();
            }
        } catch (Exception e) {
            logger.warn("Error getting customer level for {}: {}", customerId, e.getMessage());
        }
        return "default";
    }

    public void releaseLock(String staffId) {
        String lockKey = "lock:" + staffId;
        LockInfo removed = locks.remove(lockKey);
        if (removed != null) {
            logger.info("Lock released for staff {}", staffId);
        }
    }

    public void releaseLock(String staffId, String customerId) {
        String lockKey = "lock:" + staffId;
        LockInfo lockInfo = locks.get(lockKey);
        if (lockInfo != null && lockInfo.getLockOwner().equals(customerId)) {
            locks.remove(lockKey);
            logger.info("Lock released for staff {} by owner {}", staffId, customerId);
        }
    }

    public boolean isLocked(String staffId) {
        String lockKey = "lock:" + staffId;
        LockInfo lockInfo = locks.get(lockKey);
        return lockInfo != null && !lockInfo.isExpired();
    }

    public LockInfo getLockInfo(String staffId) {
        String lockKey = "lock:" + staffId;
        LockInfo lockInfo = locks.get(lockKey);
        if (lockInfo == null) return null;
        if (lockInfo.isExpired()) {
            locks.remove(lockKey);
            logger.debug("Expired lock removed for staff {}", staffId);
            return null;
        }
        return lockInfo;
    }

    public void expireAllLocks() {
        int count = locks.size();
        locks.clear();
        logger.info("All {} locks expired", count);
    }

    public int getActiveLockCount() {
        return (int) locks.values().stream().filter(lock -> !lock.isExpired()).count();
    }

    public long getHoldTimeoutForCustomer(String customerId) {
        LockTimeoutConfig config = getTimeoutConfigForCustomer(customerId);
        return config.getHoldTimeoutMs();
    }

    public long getWaitTimeoutForCustomer(String customerId) {
        LockTimeoutConfig config = getTimeoutConfigForCustomer(customerId);
        return config.getWaitTimeoutMs();
    }
}
