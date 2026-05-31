package com.parking.platform.config.service;

import com.parking.platform.common.entity.ConfigEntity;
import com.parking.platform.common.entity.ConfigVersionHistoryEntity;
import com.parking.platform.common.exception.ConfigRollbackException;
import com.parking.platform.common.exception.ConfigVersionNotFoundException;
import com.parking.platform.common.exception.ValidationException;
import com.parking.platform.common.util.IdGenerator;
import com.parking.platform.config.repository.ConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class ConfigVersionManagerService {

    private static final Logger log = LoggerFactory.getLogger(ConfigVersionManagerService.class);

    public static final int MAX_NAMESPACE_LENGTH = 200;
    public static final int MAX_PARAM_KEY_LENGTH = 500;
    public static final int MAX_CHANGE_REASON_LENGTH = 1000;
    public static final int MAX_PARAMS_SIZE = 10000;

    private final ConfigRepository repository;
    private final Map<String, ReadWriteLock> configLocks = new ConcurrentHashMap<>();

    @Autowired
    public ConfigVersionManagerService(ConfigRepository repository) {
        this.repository = repository;
    }

    public ConfigEntity createConfig(String namespace, Map<String, Object> parameters,
                                     String changeReason, String changedBy) {
        validateNamespace(namespace);
        validateParameters(parameters);
        validateChangeReason(changeReason);

        String configId = IdGenerator.generate("cfg");

        ConfigEntity config = new ConfigEntity();
        config.setId(configId);
        config.setNamespace(namespace);
        config.setVersion(1);
        config.setParameters(parameters != null ? new HashMap<>(parameters) : new HashMap<>());
        config.setEnabled(true);
        config.setAppliedAt(Instant.now());

        saveVersionHistory(config, changeReason, changedBy, true);

        return repository.save(config);
    }

    public ConfigEntity updateConfig(String configId, Map<String, Object> parameterUpdates,
                                     String changeReason, String changedBy) {
        validateChangeReason(changeReason);

        ReadWriteLock lock = getConfigLock(configId);
        lock.writeLock().lock();
        try {
            ConfigEntity config = repository.getById(configId);

            Map<String, Object> newParams = new HashMap<>(config.getParameters());
            if (parameterUpdates != null) {
                validateParameters(parameterUpdates);
                newParams.putAll(parameterUpdates);
            }

            int newVersion = config.getVersion() + 1;

            ConfigVersionHistoryEntity history = saveVersionHistory(
                    config.getId(), config.getVersion(), config.getParameters(),
                    changeReason, changedBy, false, null
            );

            config.setParameters(newParams);
            config.setVersion(newVersion);
            config.setUpdatedAt(Instant.now());
            config.setAppliedAt(Instant.now());

            return repository.save(config);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ConfigEntity rollbackToVersion(String configId, Integer targetVersion,
                                          String rollbackComment, String changedBy) {
        if (targetVersion == null || targetVersion <= 0) {
            throw new ValidationException("Target version must be a positive integer");
        }

        ReadWriteLock lock = getConfigLock(configId);
        lock.writeLock().lock();
        try {
            ConfigEntity currentConfig = repository.getById(configId);

            if (targetVersion >= currentConfig.getVersion()) {
                throw new ConfigRollbackException(
                        "Cannot rollback to version " + targetVersion +
                                ", current version is " + currentConfig.getVersion()
                );
            }

            ConfigVersionHistoryEntity history = repository.findHistoryVersion(configId, targetVersion)
                    .orElseThrow(() -> new ConfigVersionNotFoundException(configId, targetVersion));

            Map<String, Object> rollbackParams = new HashMap<>(history.getParameters());
            int newVersion = currentConfig.getVersion() + 1;

            saveVersionHistory(
                    configId, currentConfig.getVersion(), currentConfig.getParameters(),
                    "Rollback preparation", changedBy, false, null
            );

            currentConfig.setParameters(rollbackParams);
            currentConfig.setVersion(newVersion);
            currentConfig.setUpdatedAt(Instant.now());
            currentConfig.setAppliedAt(Instant.now());

            ConfigEntity saved = repository.save(currentConfig);

            saveVersionHistory(
                    configId, newVersion, rollbackParams,
                    "Rollback to version " + targetVersion, changedBy, true, rollbackComment
            );

            log.info("Config {} rolled back from version {} to version {} (new version {})",
                    configId, history.getVersion() + 1, targetVersion, newVersion);

            return saved;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ConfigEntity getConfig(String configId) {
        ReadWriteLock lock = getConfigLock(configId);
        lock.readLock().lock();
        try {
            return repository.getById(configId);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<ConfigEntity> getConfigsByNamespace(String namespace) {
        validateNamespace(namespace);
        return repository.findByNamespace(namespace);
    }

    public List<ConfigVersionHistoryEntity> getConfigHistory(String configId) {
        return repository.findHistoryByConfigId(configId);
    }

    public ConfigVersionHistoryEntity getConfigHistoryVersion(String configId, Integer version) {
        return repository.findHistoryVersion(configId, version)
                .orElseThrow(() -> new ConfigVersionNotFoundException(configId, version));
    }

    public List<ConfigVersionHistoryEntity> getRollbackPoints(String configId) {
        return repository.findRollbackPoints(configId);
    }

    public void deleteConfig(String configId) {
        repository.deleteById(configId);
        configLocks.remove(configId);
    }

    public ConfigEntity toggleConfig(String configId, boolean enabled) {
        ReadWriteLock lock = getConfigLock(configId);
        lock.writeLock().lock();
        try {
            ConfigEntity config = repository.getById(configId);
            config.setEnabled(enabled);
            config.setUpdatedAt(Instant.now());
            return repository.save(config);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ConfigEntity markRollbackPoint(String configId, String comment, String changedBy) {
        ReadWriteLock lock = getConfigLock(configId);
        lock.writeLock().lock();
        try {
            ConfigEntity config = repository.getById(configId);
            saveVersionHistory(config, "Manual rollback point", changedBy, true, comment);
            return config;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private ConfigVersionHistoryEntity saveVersionHistory(ConfigEntity config, String reason,
                                                          String changedBy, boolean isRollbackPoint,
                                                          String rollbackComment) {
        return saveVersionHistory(
                config.getId(),
                config.getVersion(),
                config.getParameters(),
                reason,
                changedBy,
                isRollbackPoint,
                rollbackComment
        );
    }

    private ConfigVersionHistoryEntity saveVersionHistory(String configId, Integer version,
                                                          Map<String, Object> parameters,
                                                          String reason, String changedBy,
                                                          boolean isRollbackPoint,
                                                          String rollbackComment) {
        ConfigVersionHistoryEntity history = new ConfigVersionHistoryEntity();
        history.setId(IdGenerator.generate("hist"));
        history.setConfigId(configId);
        history.setVersion(version);
        history.setParameters(new HashMap<>(parameters));
        history.setChangeReason(reason);
        history.setChangedBy(changedBy);
        history.setAppliedAt(Instant.now());
        history.setRollbackPoint(isRollbackPoint);
        history.setRollbackComment(rollbackComment);

        repository.saveVersionHistory(history);
        return history;
    }

    private ReadWriteLock getConfigLock(String configId) {
        return configLocks.computeIfAbsent(configId, k -> new ReentrantReadWriteLock());
    }

    private void validateNamespace(String namespace) {
        if (namespace == null) {
            throw new ValidationException("Namespace cannot be null");
        }
        if (namespace.isBlank()) {
            throw new ValidationException("Namespace cannot be blank");
        }
        if (namespace.length() > MAX_NAMESPACE_LENGTH) {
            throw new ValidationException(
                    "Namespace exceeds maximum length of " + MAX_NAMESPACE_LENGTH + " characters"
            );
        }
        if (!namespace.matches("^[a-zA-Z0-9._-]+$")) {
            throw new ValidationException(
                    "Namespace must contain only alphanumeric characters, dots, underscores, and hyphens"
            );
        }
    }

    private void validateParameters(Map<String, Object> parameters) {
        if (parameters == null) {
            return;
        }
        if (parameters.size() > MAX_PARAMS_SIZE) {
            throw new ValidationException(
                    "Parameters map exceeds maximum size of " + MAX_PARAMS_SIZE + " entries"
            );
        }
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new ValidationException("Parameter key cannot be null or blank");
            }
            if (key.length() > MAX_PARAM_KEY_LENGTH) {
                throw new ValidationException(
                        "Parameter key '" + key + "' exceeds maximum length of " + MAX_PARAM_KEY_LENGTH + " characters"
                );
            }
        }
    }

    private void validateChangeReason(String reason) {
        if (reason != null && reason.length() > MAX_CHANGE_REASON_LENGTH) {
            throw new ValidationException(
                    "Change reason exceeds maximum length of " + MAX_CHANGE_REASON_LENGTH + " characters"
            );
        }
    }

    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        List<ConfigEntity> allConfigs = repository.findAll();
        stats.put("total_configs", allConfigs.size());
        stats.put("enabled_configs", allConfigs.stream().filter(ConfigEntity::isEnabled).count());
        stats.put("disabled_configs", allConfigs.stream().filter(c -> !c.isEnabled()).count());
        return stats;
    }

    public void clearAll() {
        repository.clearAll();
        configLocks.clear();
    }
}
