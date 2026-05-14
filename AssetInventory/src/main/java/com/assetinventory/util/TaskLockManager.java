package com.assetinventory.util;

import com.assetinventory.config.LockConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class TaskLockManager {

    private static final Logger logger = LoggerFactory.getLogger(TaskLockManager.class);

    private final LockConfig lockConfig;
    private final Map<String, LockHolder> locks = new ConcurrentHashMap<>();

    public enum TaskPriority {
        URGENT("urgent"),
        NORMAL("normal"),
        SCHEDULED("scheduled");

        private final String configKey;

        TaskPriority(String configKey) {
            this.configKey = configKey;
        }

        public String getConfigKey() {
            return configKey;
        }

        public static TaskPriority fromString(String priority) {
            if (priority == null) {
                return NORMAL;
            }
            try {
                return TaskPriority.valueOf(priority.toUpperCase());
            } catch (IllegalArgumentException e) {
                return NORMAL;
            }
        }
    }

    public static class TaskLock {
        private final String taskId;
        private final String holder;
        private final long acquiredAt;
        private final long expireAt;
        private final TaskPriority priority;
        private final String priorityName;
        private final long timeoutMs;
        private final AtomicBoolean released = new AtomicBoolean(false);

        public TaskLock(String taskId, String holder, TaskPriority priority,
                       String priorityName, long timeoutMs) {
            this.taskId = taskId;
            this.holder = holder;
            this.priority = priority;
            this.priorityName = priorityName;
            this.timeoutMs = timeoutMs;
            this.acquiredAt = System.currentTimeMillis();
            this.expireAt = acquiredAt + timeoutMs;
        }

        public String getTaskId() {
            return taskId;
        }

        public String getHolder() {
            return holder;
        }

        public long getAcquiredAt() {
            return acquiredAt;
        }

        public long getExpireAt() {
            return expireAt;
        }

        public TaskPriority getPriority() {
            return priority;
        }

        public String getPriorityName() {
            return priorityName;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }

        public boolean isReleased() {
            return released.get();
        }

        public void release() {
            released.set(true);
        }

        public long getRemainingTime() {
            long remaining = expireAt - System.currentTimeMillis();
            return Math.max(0, remaining);
        }
    }

    private static class LockHolder {
        private final TaskLock lock;
        private final Thread ownerThread;

        public LockHolder(TaskLock lock, Thread ownerThread) {
            this.lock = lock;
            this.ownerThread = ownerThread;
        }

        public TaskLock getLock() {
            return lock;
        }

        public Thread getOwnerThread() {
            return ownerThread;
        }
    }

    @Autowired
    public TaskLockManager(LockConfig lockConfig) {
        this.lockConfig = lockConfig;
    }

    public TaskLock tryAcquireLock(String taskId, String holder, TaskPriority priority) {
        if (!lockConfig.isEnabled()) {
            logger.warn("Lock manager is disabled, creating dummy lock for task: {}", taskId);
            return createDummyLock(taskId, holder, priority);
        }

        String lockKey = buildLockKey(taskId);
        String priorityKey = priority.getConfigKey();

        LockHolder existing = locks.get(lockKey);
        if (existing != null) {
            TaskLock existingLock = existing.getLock();
            if (!existingLock.isExpired() && !existingLock.isReleased()) {
                logger.debug("Task {} is already locked by {}, priority: {}",
                        taskId, existingLock.getHolder(), existingLock.getPriorityName());
                return null;
            }
            logger.debug("Existing lock for task {} is expired or released, removing", taskId);
            locks.remove(lockKey);
        }

        String priorityName = lockConfig.getName(priorityKey);
        long timeoutMs = lockConfig.getTimeoutMs(priorityKey);

        TaskLock newLock = new TaskLock(taskId, holder, priority, priorityName, timeoutMs);
        LockHolder newHolder = new LockHolder(newLock, Thread.currentThread());
        locks.put(lockKey, newHolder);

        logger.info("Acquired lock for task: {}, holder: {}, priority: {}, timeout: {}ms",
                taskId, holder, priorityName, timeoutMs);

        return newLock;
    }

    public TaskLock tryAcquireLock(String taskId, String holder, String priorityName) {
        TaskPriority priority = TaskPriority.fromString(priorityName);
        return tryAcquireLock(taskId, holder, priority);
    }

    public boolean releaseLock(TaskLock lock) {
        if (lock == null) {
            return false;
        }

        String lockKey = buildLockKey(lock.getTaskId());
        LockHolder holder = locks.get(lockKey);

        if (holder != null && holder.getLock().equals(lock)) {
            lock.release();
            locks.remove(lockKey);
            logger.info("Released lock for task: {}, holder: {}",
                    lock.getTaskId(), lock.getHolder());
            return true;
        }

        logger.warn("Failed to release lock for task: {}, not the current holder",
                lock.getTaskId());
        return false;
    }

    public TaskLock getCurrentLock(String taskId) {
        String lockKey = buildLockKey(taskId);
        LockHolder holder = locks.get(lockKey);
        if (holder != null) {
            TaskLock lock = holder.getLock();
            if (lock.isExpired() || lock.isReleased()) {
                locks.remove(lockKey);
                return null;
            }
            return lock;
        }
        return null;
    }

    public boolean isLocked(String taskId) {
        return getCurrentLock(taskId) != null;
    }

    public int getActiveLockCount() {
        cleanExpiredLocks();
        return locks.size();
    }

    public void clearAllLocks() {
        locks.forEach((key, holder) -> holder.getLock().release());
        int count = locks.size();
        locks.clear();
        logger.info("Cleared all {} locks", count);
    }

    public long getTimeoutForPriority(TaskPriority priority) {
        return lockConfig.getTimeoutMs(priority.getConfigKey());
    }

    public int getLevelForPriority(TaskPriority priority) {
        return lockConfig.getLevel(priority.getConfigKey());
    }

    public String getNameForPriority(TaskPriority priority) {
        return lockConfig.getName(priority.getConfigKey());
    }

    private void cleanExpiredLocks() {
        int beforeCount = locks.size();
        locks.entrySet().removeIf(entry -> {
            TaskLock lock = entry.getValue().getLock();
            boolean shouldRemove = lock.isExpired() || lock.isReleased();
            if (shouldRemove) {
                logger.debug("Removing expired/released lock for task: {}", lock.getTaskId());
            }
            return shouldRemove;
        });
        int afterCount = locks.size();
        if (beforeCount != afterCount) {
            logger.debug("Cleaned {} expired locks", beforeCount - afterCount);
        }
    }

    private TaskLock createDummyLock(String taskId, String holder, TaskPriority priority) {
        String priorityKey = priority.getConfigKey();
        String priorityName = lockConfig.getName(priorityKey);
        long timeoutMs = lockConfig.getTimeoutMs(priorityKey);
        return new TaskLock(taskId, holder, priority, priorityName, timeoutMs);
    }

    private String buildLockKey(String taskId) {
        return "task:" + taskId;
    }
}
