package com.library.librarymgmt.util;

import com.library.librarymgmt.config.LibraryConfig;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class LockManager {

    private final Map<String, LockEntry> locks = new ConcurrentHashMap<>();
    private final LibraryConfig libraryConfig;

    public LockManager(LibraryConfig libraryConfig) {
        this.libraryConfig = libraryConfig;
    }

    public boolean acquireLock(String key, int timeoutSeconds) {
        LockEntry existingLock = locks.get(key);
        if (existingLock != null && !existingLock.isExpired()) {
            return false;
        }

        LockEntry newLock = new LockEntry(timeoutSeconds);
        LockEntry previous = locks.putIfAbsent(key, newLock);
        return previous == null || previous.isExpired();
    }

    public boolean acquireLock(String key, String readerType) {
        int timeoutSeconds = libraryConfig.getLock().getTimeoutByReaderType(readerType);
        return acquireLock(key, timeoutSeconds);
    }

    public int getLockTimeoutByReaderType(String readerType) {
        return libraryConfig.getLock().getTimeoutByReaderType(readerType);
    }

    public boolean releaseLock(String key) {
        return locks.remove(key) != null;
    }

    public boolean isLocked(String key) {
        LockEntry lock = locks.get(key);
        return lock != null && !lock.isExpired();
    }

    public static class LockEntry {
        private final long expireTime;
        private final AtomicBoolean active = new AtomicBoolean(true);

        public LockEntry(int timeoutSeconds) {
            this.expireTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expireTime || !active.get();
        }
    }
}
