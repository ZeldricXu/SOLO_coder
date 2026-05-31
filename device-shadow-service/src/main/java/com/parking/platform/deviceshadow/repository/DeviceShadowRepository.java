package com.parking.platform.deviceshadow.repository;

import com.parking.platform.common.entity.DeviceShadowEntity;
import com.parking.platform.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class DeviceShadowRepository {

    private final Map<String, DeviceShadowEntity> shadows = new ConcurrentHashMap<>();
    private final Map<String, DeviceShadowEntity> shadowsByDevice = new ConcurrentHashMap<>();
    private volatile boolean simulateStorageFailure = false;
    private volatile boolean simulateSyncFailure = false;

    public DeviceShadowEntity save(DeviceShadowEntity shadow) {
        checkStorageHealth();
        shadows.put(shadow.getId(), shadow);
        shadowsByDevice.put(shadow.getDeviceId(), shadow);
        return shadow;
    }

    public Optional<DeviceShadowEntity> findById(String id) {
        checkStorageHealth();
        return Optional.ofNullable(shadows.get(id));
    }

    public Optional<DeviceShadowEntity> findByDeviceId(String deviceId) {
        checkStorageHealth();
        return Optional.ofNullable(shadowsByDevice.get(deviceId));
    }

    public DeviceShadowEntity getById(String id) {
        return findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Device shadow not found: " + id));
    }

    public DeviceShadowEntity getByDeviceId(String deviceId) {
        return findByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device shadow not found for device: " + deviceId));
    }

    public void deleteById(String id) {
        checkStorageHealth();
        DeviceShadowEntity shadow = shadows.get(id);
        if (shadow != null) {
            shadows.remove(id);
            shadowsByDevice.remove(shadow.getDeviceId());
        }
    }

    public List<DeviceShadowEntity> findAll() {
        checkStorageHealth();
        return new ArrayList<>(shadows.values());
    }

    public boolean existsByDeviceId(String deviceId) {
        checkStorageHealth();
        return shadowsByDevice.containsKey(deviceId);
    }

    public void setSimulateStorageFailure(boolean simulate) {
        this.simulateStorageFailure = simulate;
    }

    public void setSimulateSyncFailure(boolean simulate) {
        this.simulateSyncFailure = simulate;
    }

    public boolean shouldSimulateSyncFailure() {
        return simulateSyncFailure;
    }

    private void checkStorageHealth() {
        if (simulateStorageFailure) {
            throw new RuntimeException("Storage layer failure - database unavailable");
        }
    }

    public void clearAll() {
        shadows.clear();
        shadowsByDevice.clear();
        simulateStorageFailure = false;
        simulateSyncFailure = false;
    }
}
