package com.parking.platform.config.repository;

import com.parking.platform.common.entity.ConfigEntity;
import com.parking.platform.common.entity.ConfigVersionHistoryEntity;
import com.parking.platform.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class ConfigRepository {

    private final Map<String, ConfigEntity> configs = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, ConfigVersionHistoryEntity>> versionHistory = new ConcurrentHashMap<>();
    private volatile boolean simulateStorageFailure = false;

    public ConfigEntity save(ConfigEntity config) {
        checkStorageHealth();
        configs.put(config.getId(), config);
        return config;
    }

    public Optional<ConfigEntity> findById(String id) {
        checkStorageHealth();
        return Optional.ofNullable(configs.get(id));
    }

    public ConfigEntity getById(String id) {
        return findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Config not found: " + id));
    }

    public List<ConfigEntity> findByNamespace(String namespace) {
        checkStorageHealth();
        return configs.values().stream()
                .filter(c -> namespace.equals(c.getNamespace()))
                .collect(Collectors.toList());
    }

    public void deleteById(String id) {
        checkStorageHealth();
        if (!configs.containsKey(id)) {
            throw new ResourceNotFoundException("Config not found: " + id);
        }
        configs.remove(id);
        versionHistory.remove(id);
    }

    public List<ConfigEntity> findAll() {
        checkStorageHealth();
        return new ArrayList<>(configs.values());
    }

    public void saveVersionHistory(ConfigVersionHistoryEntity history) {
        checkStorageHealth();
        versionHistory
                .computeIfAbsent(history.getConfigId(), k -> new ConcurrentHashMap<>())
                .put(history.getVersion(), history);
    }

    public Optional<ConfigVersionHistoryEntity> findHistoryVersion(String configId, Integer version) {
        checkStorageHealth();
        Map<Integer, ConfigVersionHistoryEntity> versions = versionHistory.get(configId);
        if (versions == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(versions.get(version));
    }

    public List<ConfigVersionHistoryEntity> findHistoryByConfigId(String configId) {
        checkStorageHealth();
        Map<Integer, ConfigVersionHistoryEntity> versions = versionHistory.get(configId);
        if (versions == null) {
            return new ArrayList<>();
        }
        return versions.values().stream()
                .sorted((a, b) -> b.getVersion().compareTo(a.getVersion()))
                .collect(Collectors.toList());
    }

    public List<ConfigVersionHistoryEntity> findRollbackPoints(String configId) {
        return findHistoryByConfigId(configId).stream()
                .filter(ConfigVersionHistoryEntity::isRollbackPoint)
                .collect(Collectors.toList());
    }

    public void setSimulateStorageFailure(boolean simulate) {
        this.simulateStorageFailure = simulate;
    }

    private void checkStorageHealth() {
        if (simulateStorageFailure) {
            throw new RuntimeException("Storage layer failure - database unavailable");
        }
    }

    public void clearAll() {
        configs.clear();
        versionHistory.clear();
        simulateStorageFailure = false;
    }
}
