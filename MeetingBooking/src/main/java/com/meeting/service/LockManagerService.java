package com.meeting.service;

import com.meeting.config.LockConfig;
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
public class LockManagerService {

    private final LockConfig lockConfig;

    private final Map<String, RoomLock> roomLocks = new ConcurrentHashMap<>();

    public static class LockResult {
        private final boolean acquired;
        private final String lockOwner;
        private final String message;
        private final long timeoutUsed;

        public LockResult(boolean acquired, String lockOwner, String message, long timeoutUsed) {
            this.acquired = acquired;
            this.lockOwner = lockOwner;
            this.message = message;
            this.timeoutUsed = timeoutUsed;
        }

        public boolean isAcquired() {
            return acquired;
        }

        public String getLockOwner() {
            return lockOwner;
        }

        public String getMessage() {
            return message;
        }

        public long getTimeoutUsed() {
            return timeoutUsed;
        }
    }

    public static class RoomLock {
        private final ReentrantLock lock = new ReentrantLock();
        private String lockOwner;
        private LocalDateTime acquireTime;
        private long timeoutSeconds;

        public boolean tryLock(String owner, long timeoutSeconds) {
            try {
                boolean acquired = lock.tryLock(timeoutSeconds, TimeUnit.SECONDS);
                if (acquired) {
                    this.lockOwner = owner;
                    this.acquireTime = LocalDateTime.now();
                    this.timeoutSeconds = timeoutSeconds;
                    log.info("获取锁成功: room={}, owner={}, timeout={}s", getLockKey(owner), owner, timeoutSeconds);
                    return true;
                }
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("获取锁被中断: owner={}", owner);
                return false;
            }
        }

        public void unlock(String owner) {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                this.lockOwner = null;
                this.acquireTime = null;
                log.info("释放锁成功: room={}", getLockKey(owner));
            } else {
                log.warn("尝试释放非当前线程持有的锁: owner={}", owner);
            }
        }

        public boolean isLocked() {
            return lock.isLocked();
        }

        public boolean isExpired() {
            if (acquireTime == null) {
                return false;
            }
            LocalDateTime expireTime = acquireTime.plusSeconds(timeoutSeconds);
            return LocalDateTime.now().isAfter(expireTime);
        }

        public String getLockOwner() {
            return lockOwner;
        }

        public long getTimeoutSeconds() {
            return timeoutSeconds;
        }
    }

    private String getLockKey(String roomId) {
        return "ROOM_LOCK_" + roomId;
    }

    public LockResult acquireLock(String roomId, String ownerId, String meetingType) {
        long timeoutSeconds = getLockTimeoutByType(meetingType);
        String lockKey = getLockKey(roomId);

        log.debug("尝试获取锁: roomId={}, ownerId={}, meetingType={}, timeout={}s",
                roomId, ownerId, meetingType, timeoutSeconds);

        RoomLock roomLock = roomLocks.computeIfAbsent(lockKey, k -> new RoomLock());

        if (roomLock.isLocked() && !roomLock.isExpired()) {
            String currentOwner = roomLock.getLockOwner();
            log.warn("会议室已被锁定: room={}, currentOwner={}, requestOwner={}", roomId, currentOwner, ownerId);
            return new LockResult(false, currentOwner, "会议室正在被其他会议预约占用", timeoutSeconds);
        }

        boolean acquired = roomLock.tryLock(ownerId, timeoutSeconds);
        if (acquired) {
            return new LockResult(true, ownerId, "锁获取成功", timeoutSeconds);
        } else {
            return new LockResult(false, roomLock.getLockOwner(), "获取锁超时", timeoutSeconds);
        }
    }

    public LockResult acquireLock(String roomId, String ownerId) {
        return acquireLock(roomId, ownerId, lockConfig.getDefaultConfig().getDescription() != null ?
                lockConfig.getDefaultConfig().getDescription() : "regular");
    }

    public void releaseLock(String roomId, String ownerId) {
        String lockKey = getLockKey(roomId);
        RoomLock roomLock = roomLocks.get(lockKey);
        if (roomLock != null) {
            roomLock.unlock(ownerId);
        }
    }

    public boolean isRoomLocked(String roomId) {
        String lockKey = getLockKey(roomId);
        RoomLock roomLock = roomLocks.get(lockKey);
        return roomLock != null && roomLock.isLocked();
    }

    public long getLockTimeoutByType(String meetingType) {
        if (!lockConfig.isEnabled()) {
            return lockConfig.getDefaultTimeoutSeconds();
        }
        return lockConfig.getTimeoutForType(meetingType);
    }

    public int getLockPriorityByType(String meetingType) {
        return lockConfig.getPriorityForType(meetingType);
    }

    public LockConfig.LockTypeConfig getLockConfigByType(String meetingType) {
        return lockConfig.getConfigForType(meetingType);
    }

    public void forceReleaseLock(String roomId) {
        String lockKey = getLockKey(roomId);
        RoomLock roomLock = roomLocks.get(lockKey);
        if (roomLock != null && roomLock.isLocked()) {
            roomLock.unlock("FORCE_RELEASE");
            log.info("强制释放锁: room={}", roomId);
        }
    }

    public void releaseAllLocks() {
        for (String key : roomLocks.keySet()) {
            RoomLock roomLock = roomLocks.get(key);
            if (roomLock != null && roomLock.isLocked()) {
                roomLock.unlock("BATCH_RELEASE");
            }
        }
        log.info("释放所有锁: count={}", roomLocks.size());
    }

    public boolean hasConfigForType(String meetingType) {
        return lockConfig.hasConfigForType(meetingType);
    }

    public void addOrUpdateLockConfig(String type, LockConfig.LockTypeConfig config) {
        lockConfig.addOrUpdateConfig(type, config);
        log.info("添加/更新锁定配置: type={}, timeout={}s, priority={}",
                type, config.getLockTimeoutSeconds(), config.getPriority());
    }

    public void removeLockConfig(String type) {
        lockConfig.removeConfig(type);
        log.info("移除锁定配置: type={}", type);
    }

    public Map<String, LockConfig.LockTypeConfig> getAllLockConfigs() {
        return lockConfig.getTypeConfigs();
    }
}
