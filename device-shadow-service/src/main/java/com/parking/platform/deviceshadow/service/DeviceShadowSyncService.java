package com.parking.platform.deviceshadow.service;

import com.parking.platform.common.entity.DeviceShadowEntity;
import com.parking.platform.common.exception.DeviceShadowSyncException;
import com.parking.platform.common.exception.ValidationException;
import com.parking.platform.common.util.IdGenerator;
import com.parking.platform.deviceshadow.repository.DeviceShadowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class DeviceShadowSyncService {

    private static final Logger log = LoggerFactory.getLogger(DeviceShadowSyncService.class);

    public static final int MAX_DEVICE_ID_LENGTH = 200;
    public static final int MAX_STATE_KEY_LENGTH = 500;
    public static final int MAX_STATE_SIZE = 10000;
    public static final int DEFAULT_RETRY_ATTEMPTS = 3;
    public static final long DEFAULT_RETRY_DELAY_MS = 1000;
    public static final long DEFAULT_SYNC_TIMEOUT_MS = 30000;

    private final DeviceShadowRepository repository;
    private final Map<String, ReentrantLock> deviceLocks = new ConcurrentHashMap<>();
    private final ExecutorService syncExecutor = Executors.newCachedThreadPool();

    private int maxRetryAttempts = DEFAULT_RETRY_ATTEMPTS;
    private long retryDelayMs = DEFAULT_RETRY_DELAY_MS;
    private long syncTimeoutMs = DEFAULT_SYNC_TIMEOUT_MS;

    @Autowired
    public DeviceShadowSyncService(DeviceShadowRepository repository) {
        this.repository = repository;
    }

    public DeviceShadowEntity createShadow(String deviceId) {
        validateDeviceId(deviceId);

        if (repository.existsByDeviceId(deviceId)) {
            throw new ValidationException("Device shadow already exists for device: " + deviceId);
        }

        DeviceShadowEntity shadow = new DeviceShadowEntity(deviceId);
        shadow.setId(IdGenerator.generate("shadow"));
        return repository.save(shadow);
    }

    public DeviceShadowEntity getOrCreateShadow(String deviceId) {
        validateDeviceId(deviceId);
        return repository.findByDeviceId(deviceId)
                .orElseGet(() -> createShadow(deviceId));
    }

    public DeviceShadowEntity getShadowByDeviceId(String deviceId) {
        validateDeviceId(deviceId);
        return repository.getByDeviceId(deviceId);
    }

    public DeviceShadowEntity updateDesiredState(String deviceId, Map<String, Object> desiredUpdates) {
        validateDeviceId(deviceId);
        validateStateMap(desiredUpdates, "desired");

        ReentrantLock lock = getDeviceLock(deviceId);
        lock.lock();
        try {
            DeviceShadowEntity shadow = getOrCreateShadow(deviceId);
            shadow.updateDesired(desiredUpdates);
            shadow.setUpdatedAt(Instant.now());
            return repository.save(shadow);
        } finally {
            lock.unlock();
        }
    }

    public DeviceShadowEntity updateReportedState(String deviceId, Map<String, Object> reportedUpdates) {
        validateDeviceId(deviceId);
        validateStateMap(reportedUpdates, "reported");

        ReentrantLock lock = getDeviceLock(deviceId);
        lock.lock();
        try {
            DeviceShadowEntity shadow = getOrCreateShadow(deviceId);
            shadow.updateReported(reportedUpdates);
            shadow.setUpdatedAt(Instant.now());
            return repository.save(shadow);
        } finally {
            lock.unlock();
        }
    }

    public DeviceShadowEntity syncWithDevice(String deviceId) throws DeviceShadowSyncException {
        validateDeviceId(deviceId);

        ReentrantLock lock = getDeviceLock(deviceId);
        lock.lock();
        try {
            DeviceShadowEntity shadow = repository.getByDeviceId(deviceId);

            if (shadow.isSynced()) {
                log.debug("Shadow for device {} is already synced", deviceId);
                return shadow;
            }

            shadow.setStatus("syncing");
            repository.save(shadow);

            performSyncWithRetry(shadow);

            shadow.markSynced();
            shadow.setUpdatedAt(Instant.now());
            return repository.save(shadow);

        } catch (Exception e) {
            DeviceShadowEntity shadow = repository.findByDeviceId(deviceId).orElse(null);
            if (shadow != null) {
                shadow.setStatus("sync_failed");
                repository.save(shadow);
            }
            if (e instanceof DeviceShadowSyncException) {
                throw e;
            }
            throw new DeviceShadowSyncException("Sync failed for device " + deviceId + ": " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    public Future<DeviceShadowEntity> syncWithDeviceAsync(String deviceId) {
        validateDeviceId(deviceId);
        return syncExecutor.submit(() -> syncWithDevice(deviceId));
    }

    private void performSyncWithRetry(DeviceShadowEntity shadow) throws DeviceShadowSyncException {
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            try {
                performDeviceSync(shadow);
                return;
            } catch (Exception e) {
                lastException = e;
                log.warn("Sync attempt {} failed for device {}: {}",
                        attempt, shadow.getDeviceId(), e.getMessage());

                if (attempt < maxRetryAttempts) {
                    try {
                        long delay = retryDelayMs * (long) Math.pow(2, attempt - 1);
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new DeviceShadowSyncException("Sync interrupted", ie);
                    }
                }
            }
        }

        throw new DeviceShadowSyncException(
                "Sync failed after " + maxRetryAttempts + " attempts for device " +
                        shadow.getDeviceId() + ": " + (lastException != null ? lastException.getMessage() : "unknown error"),
                lastException
        );
    }

    private void performDeviceSync(DeviceShadowEntity shadow) throws DeviceShadowSyncException {
        if (repository.shouldSimulateSyncFailure()) {
            throw new DeviceShadowSyncException("Simulated device connection failure");
        }

        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceShadowSyncException("Sync interrupted during device communication", e);
        }
    }

    public List<DeviceShadowEntity> batchSync(List<String> deviceIds, long timeoutMs) throws TimeoutException {
        List<Future<DeviceShadowEntity>> futures = new ArrayList<>();

        for (String deviceId : deviceIds) {
            futures.add(syncWithDeviceAsync(deviceId));
        }

        List<DeviceShadowEntity> results = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;

        for (Future<DeviceShadowEntity> future : futures) {
            try {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new TimeoutException("Batch sync timed out");
                }
                results.add(future.get(remaining, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                throw e;
            } catch (Exception e) {
                if (e.getCause() instanceof DeviceShadowSyncException) {
                    throw (DeviceShadowSyncException) e.getCause();
                }
                throw new DeviceShadowSyncException("Batch sync failed: " + e.getMessage(), e);
            }
        }

        return results;
    }

    public Map<String, Object> getDiff(String deviceId) {
        DeviceShadowEntity shadow = getShadowByDeviceId(deviceId);
        Map<String, Object> diff = new HashMap<>();

        for (Map.Entry<String, Object> entry : shadow.getDesired().entrySet()) {
            Object desiredValue = entry.getValue();
            Object reportedValue = shadow.getReported().get(entry.getKey());

            if (desiredValue != null && !desiredValue.equals(reportedValue)) {
                diff.put(entry.getKey(), Map.of(
                        "desired", desiredValue,
                        "reported", reportedValue
                ));
            }
        }

        return diff;
    }

    public void deleteShadow(String deviceId) {
        validateDeviceId(deviceId);
        DeviceShadowEntity shadow = repository.getByDeviceId(deviceId);
        repository.deleteById(shadow.getId());
        deviceLocks.remove(deviceId);
    }

    private ReentrantLock getDeviceLock(String deviceId) {
        return deviceLocks.computeIfAbsent(deviceId, k -> new ReentrantLock());
    }

    private void validateDeviceId(String deviceId) {
        if (deviceId == null) {
            throw new ValidationException("Device ID cannot be null");
        }
        if (deviceId.isBlank()) {
            throw new ValidationException("Device ID cannot be blank");
        }
        if (deviceId.length() > MAX_DEVICE_ID_LENGTH) {
            throw new ValidationException(
                    "Device ID exceeds maximum length of " + MAX_DEVICE_ID_LENGTH + " characters"
            );
        }
        if (!deviceId.matches("^[a-zA-Z0-9._-]+$")) {
            throw new ValidationException(
                    "Device ID must contain only alphanumeric characters, dots, underscores, and hyphens"
            );
        }
    }

    private void validateStateMap(Map<String, Object> state, String stateType) {
        if (state == null) {
            throw new ValidationException(stateType + " state cannot be null");
        }
        if (state.isEmpty()) {
            throw new ValidationException(stateType + " state cannot be empty");
        }
        if (state.size() > MAX_STATE_SIZE) {
            throw new ValidationException(
                    stateType + " state exceeds maximum size of " + MAX_STATE_SIZE + " entries"
            );
        }
        for (Map.Entry<String, Object> entry : state.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new ValidationException(stateType + " state key cannot be null or blank");
            }
            if (key.length() > MAX_STATE_KEY_LENGTH) {
                throw new ValidationException(
                        stateType + " state key '" + key + "' exceeds maximum length of " +
                                MAX_STATE_KEY_LENGTH + " characters"
                );
            }
        }
    }

    public void setMaxRetryAttempts(int maxRetryAttempts) {
        this.maxRetryAttempts = maxRetryAttempts;
    }

    public void setRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }

    public void setSyncTimeoutMs(long syncTimeoutMs) {
        this.syncTimeoutMs = syncTimeoutMs;
    }

    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        List<DeviceShadowEntity> allShadows = repository.findAll();
        stats.put("total_shadows", allShadows.size());
        stats.put("synced_shadows", allShadows.stream().filter(DeviceShadowEntity::isSynced).count());
        stats.put("pending_shadows", allShadows.stream().filter(s -> "pending_sync".equals(s.getStatus())).count());
        stats.put("failed_shadows", allShadows.stream().filter(s -> "sync_failed".equals(s.getStatus())).count());
        return stats;
    }

    public void shutdown() {
        syncExecutor.shutdown();
    }

    public void clearAll() {
        repository.clearAll();
        deviceLocks.clear();
    }
}
