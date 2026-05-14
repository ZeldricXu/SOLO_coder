package com.deviceops.service.config;

import com.deviceops.config.model.AlertConfig;
import com.deviceops.config.model.DeviceTypeConfig;
import com.deviceops.config.model.TaskLockConfig;
import com.deviceops.entity.SystemConfig;
import com.deviceops.repository.SystemConfigRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DynamicConfigService {

    @Autowired
    private SystemConfigRepository configRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private final Map<String, AlertConfig> alertConfigCache = new ConcurrentHashMap<>();
    private final Map<String, TaskLockConfig> taskLockConfigCache = new ConcurrentHashMap<>();
    private final Map<String, DeviceTypeConfig> deviceTypeConfigCache = new ConcurrentHashMap<>();

    private static final String ALERT_CONFIG_PREFIX = "alert.retry.";
    private static final String TASK_LOCK_CONFIG_PREFIX = "task.lock.";
    private static final String DEVICE_TYPE_CONFIG_PREFIX = "device.type.";

    @PostConstruct
    public void init() {
        initializeDefaultConfigs();
        refreshCache();
    }

    @Scheduled(fixedRate = 30000)
    public void refreshCache() {
        List<SystemConfig> allConfigs = configRepository.findAll();

        alertConfigCache.clear();
        taskLockConfigCache.clear();
        deviceTypeConfigCache.clear();

        for (SystemConfig config : allConfigs) {
            if (!Boolean.TRUE.equals(config.getIsActive())) {
                continue;
            }

            try {
                if (config.getConfigKey().startsWith(ALERT_CONFIG_PREFIX)) {
                    AlertConfig alertConfig = objectMapper.readValue(config.getConfigValue(), AlertConfig.class);
                    alertConfigCache.put(alertConfig.getFaultLevel(), alertConfig);
                } else if (config.getConfigKey().startsWith(TASK_LOCK_CONFIG_PREFIX)) {
                    TaskLockConfig lockConfig = objectMapper.readValue(config.getConfigValue(), TaskLockConfig.class);
                    taskLockConfigCache.put(lockConfig.getPriorityLevel(), lockConfig);
                } else if (config.getConfigKey().startsWith(DEVICE_TYPE_CONFIG_PREFIX)) {
                    DeviceTypeConfig typeConfig = objectMapper.readValue(config.getConfigValue(), DeviceTypeConfig.class);
                    if (Boolean.TRUE.equals(typeConfig.getEnabled())) {
                        deviceTypeConfigCache.put(typeConfig.getTypeCode(), typeConfig);
                    }
                }
            } catch (JsonProcessingException e) {
                System.err.println("Error parsing config: " + config.getConfigKey());
            }
        }
    }

    @Transactional
    public void initializeDefaultConfigs() {
        if (!configRepository.existsByConfigKey(ALERT_CONFIG_PREFIX + "high")) {
            saveAlertConfig(new AlertConfig("high", 5, 30, true), "紧急故障预警重试配置");
        }
        if (!configRepository.existsByConfigKey(ALERT_CONFIG_PREFIX + "medium")) {
            saveAlertConfig(new AlertConfig("medium", 3, 60, true), "一般故障预警重试配置");
        }
        if (!configRepository.existsByConfigKey(ALERT_CONFIG_PREFIX + "low")) {
            saveAlertConfig(new AlertConfig("low", 1, 300, false), "轻微故障预警重试配置");
        }

        if (!configRepository.existsByConfigKey(TASK_LOCK_CONFIG_PREFIX + "high")) {
            saveTaskLockConfig(new TaskLockConfig("high", 1800, 60, true), "高优先级任务锁定配置");
        }
        if (!configRepository.existsByConfigKey(TASK_LOCK_CONFIG_PREFIX + "medium")) {
            saveTaskLockConfig(new TaskLockConfig("medium", 3600, 300, true), "中优先级任务锁定配置");
        }
        if (!configRepository.existsByConfigKey(TASK_LOCK_CONFIG_PREFIX + "low")) {
            saveTaskLockConfig(new TaskLockConfig("low", 7200, 600, false), "低优先级任务锁定配置");
        }
    }

    @Transactional
    public void saveAlertConfig(AlertConfig config, String description) {
        String key = ALERT_CONFIG_PREFIX + config.getFaultLevel();
        saveConfig(key, config, "alert", description);
    }

    @Transactional
    public void saveTaskLockConfig(TaskLockConfig config, String description) {
        String key = TASK_LOCK_CONFIG_PREFIX + config.getPriorityLevel();
        saveConfig(key, config, "task_lock", description);
    }

    @Transactional
    public void saveDeviceTypeConfig(DeviceTypeConfig config, String description) {
        String key = DEVICE_TYPE_CONFIG_PREFIX + config.getTypeCode();
        saveConfig(key, config, "device_type", description);
    }

    @Transactional
    public void saveConfig(String key, Object value, String type, String description) {
        try {
            String jsonValue = objectMapper.writeValueAsString(value);

            Optional<SystemConfig> existing = configRepository.findByConfigKey(key);
            SystemConfig config;

            if (existing.isPresent()) {
                config = existing.get();
                config.setConfigValue(jsonValue);
                if (description != null) {
                    config.setDescription(description);
                }
            } else {
                config = new SystemConfig();
                config.setConfigKey(key);
                config.setConfigValue(jsonValue);
                config.setConfigType(type);
                config.setDescription(description);
                config.setIsActive(true);
            }

            configRepository.save(config);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize config", e);
        }
    }

    public AlertConfig getAlertConfig(String faultLevel) {
        AlertConfig config = alertConfigCache.get(faultLevel);
        if (config == null) {
            config = getDefaultAlertConfig(faultLevel);
        }
        return config;
    }

    public TaskLockConfig getTaskLockConfig(String priorityLevel) {
        TaskLockConfig config = taskLockConfigCache.get(priorityLevel);
        if (config == null) {
            config = getDefaultTaskLockConfig(priorityLevel);
        }
        return config;
    }

    public DeviceTypeConfig getDeviceTypeConfig(String typeCode) {
        return deviceTypeConfigCache.get(typeCode);
    }

    public Collection<DeviceTypeConfig> getAllDeviceTypeConfigs() {
        return new ArrayList<>(deviceTypeConfigCache.values());
    }

    public int getAlertMaxRetries(String faultLevel) {
        AlertConfig config = getAlertConfig(faultLevel);
        return config.getMaxRetries();
    }

    public int getTaskLockTimeout(String priorityLevel) {
        TaskLockConfig config = getTaskLockConfig(priorityLevel);
        return config.getLockTimeoutSeconds();
    }

    public boolean isDeviceTypeEnabled(String typeCode) {
        DeviceTypeConfig config = deviceTypeConfigCache.get(typeCode);
        return config != null && Boolean.TRUE.equals(config.getEnabled());
    }

    private AlertConfig getDefaultAlertConfig(String faultLevel) {
        return switch (faultLevel) {
            case "high" -> new AlertConfig("high", 5, 30, true);
            case "medium" -> new AlertConfig("medium", 3, 60, true);
            default -> new AlertConfig("low", 1, 300, false);
        };
    }

    private TaskLockConfig getDefaultTaskLockConfig(String priorityLevel) {
        return switch (priorityLevel) {
            case "high" -> new TaskLockConfig("high", 1800, 60, true);
            case "medium" -> new TaskLockConfig("medium", 3600, 300, true);
            default -> new TaskLockConfig("low", 7200, 600, false);
        };
    }

    @Transactional
    public void addDeviceTypeConfig(DeviceTypeConfig config) {
        saveDeviceTypeConfig(config, "用户自定义设备类型: " + config.getTypeName());
        refreshCache();
    }

    @Transactional
    public void removeDeviceTypeConfig(String typeCode) {
        String key = DEVICE_TYPE_CONFIG_PREFIX + typeCode;
        if (configRepository.existsByConfigKey(key)) {
            configRepository.deleteByConfigKey(key);
            refreshCache();
        }
    }

    @Transactional
    public void updateDeviceTypeConfig(DeviceTypeConfig config) {
        saveDeviceTypeConfig(config, "更新设备类型配置: " + config.getTypeName());
        refreshCache();
    }

    @Transactional
    public void updateAlertConfig(AlertConfig config) {
        saveAlertConfig(config, "更新预警配置: " + config.getFaultLevel());
        refreshCache();
    }

    @Transactional
    public void updateTaskLockConfig(TaskLockConfig config) {
        saveTaskLockConfig(config, "更新任务锁定配置: " + config.getPriorityLevel());
        refreshCache();
    }
}
