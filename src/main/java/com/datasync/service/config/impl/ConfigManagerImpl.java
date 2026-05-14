package com.datasync.service.config.impl;

import com.datasync.common.Constants;
import com.datasync.model.ConflictStrategyConfig;
import com.datasync.model.DataSourceConfig;
import com.datasync.model.SyncTaskConfig;
import com.datasync.service.config.ConfigManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ConfigManagerImpl implements ConfigManager {

    private static final Logger logger = LoggerFactory.getLogger(ConfigManagerImpl.class);

    public static final String REDIS_KEY_PREFIX_STRATEGY = "conflict_strategy:";

    private final Map<String, DataSourceConfig> dataSourceCache = new ConcurrentHashMap<>();
    private final Map<String, SyncTaskConfig> taskCache = new ConcurrentHashMap<>();
    private final Map<String, ConflictStrategyConfig> strategyCache = new ConcurrentHashMap<>();

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        try {
            loadFromRedis();
        } catch (Exception e) {
            logger.warn("Failed to load configs from Redis, using in-memory storage only", e);
        }
        logger.info("ConfigManager initialized with {} data sources and {} tasks",
                dataSourceCache.size(), taskCache.size());
    }

    private void loadFromRedis() {
        try {
            Set<String> dataSourceKeys = redisTemplate.keys(Constants.REDIS_KEY_PREFIX_DATASOURCE + "*");
            if (dataSourceKeys != null) {
                for (String key : dataSourceKeys) {
                    String value = redisTemplate.opsForValue().get(key);
                    if (value != null) {
                        DataSourceConfig config = objectMapper.readValue(value, DataSourceConfig.class);
                        dataSourceCache.put(config.getSourceId(), config);
                    }
                }
            }

            Set<String> taskKeys = redisTemplate.keys(Constants.REDIS_KEY_PREFIX_TASK + "*");
            if (taskKeys != null) {
                for (String key : taskKeys) {
                    String value = redisTemplate.opsForValue().get(key);
                    if (value != null) {
                        SyncTaskConfig config = objectMapper.readValue(value, SyncTaskConfig.class);
                        taskCache.put(config.getTaskId(), config);
                    }
                }
            }

            loadAllStrategiesFromPersistence();
        } catch (Exception e) {
            logger.error("Error loading configs from Redis", e);
        }
    }

    private void saveToRedis(String key, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json);
        } catch (Exception e) {
            logger.warn("Failed to save config to Redis: {}", key, e);
        }
    }

    private void deleteFromRedis(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            logger.warn("Failed to delete config from Redis: {}", key, e);
        }
    }

    @Override
    public DataSourceConfig saveDataSource(DataSourceConfig config) {
        if (config.getSourceId() == null || config.getSourceId().isEmpty()) {
            config.setSourceId("ds_" + UUID.randomUUID().toString().substring(0, 8));
        }
        dataSourceCache.put(config.getSourceId(), config);
        saveToRedis(Constants.REDIS_KEY_PREFIX_DATASOURCE + config.getSourceId(), config);
        logger.info("Saved data source: {}", config.getSourceId());
        return config;
    }

    @Override
    public Optional<DataSourceConfig> getDataSource(String sourceId) {
        return Optional.ofNullable(dataSourceCache.get(sourceId));
    }

    @Override
    public List<DataSourceConfig> getAllDataSources() {
        return new ArrayList<>(dataSourceCache.values());
    }

    @Override
    public boolean deleteDataSource(String sourceId) {
        boolean hasTasks = taskCache.values().stream()
                .anyMatch(t -> sourceId.equals(t.getSourceId()) || sourceId.equals(t.getTargetId()));
        if (hasTasks) {
            throw new IllegalStateException("Cannot delete data source with active tasks");
        }
        DataSourceConfig removed = dataSourceCache.remove(sourceId);
        if (removed != null) {
            deleteFromRedis(Constants.REDIS_KEY_PREFIX_DATASOURCE + sourceId);
            logger.info("Deleted data source: {}", sourceId);
            return true;
        }
        return false;
    }

    @Override
    public DataSourceConfig updateDataSourceStatus(String sourceId, String status) {
        DataSourceConfig config = dataSourceCache.get(sourceId);
        if (config == null) {
            throw new NoSuchElementException("Data source not found: " + sourceId);
        }
        config.setStatus(status);
        saveToRedis(Constants.REDIS_KEY_PREFIX_DATASOURCE + config.getSourceId(), config);
        logger.info("Updated data source status: {} -> {}", sourceId, status);
        return config;
    }

    @Override
    public SyncTaskConfig saveTask(SyncTaskConfig config) {
        if (config.getTaskId() == null || config.getTaskId().isEmpty()) {
            config.setTaskId("task_" + UUID.randomUUID().toString().substring(0, 8));
        }
        taskCache.put(config.getTaskId(), config);
        saveToRedis(Constants.REDIS_KEY_PREFIX_TASK + config.getTaskId(), config);
        logger.info("Saved task: {}", config.getTaskId());
        return config;
    }

    @Override
    public Optional<SyncTaskConfig> getTask(String taskId) {
        return Optional.ofNullable(taskCache.get(taskId));
    }

    @Override
    public List<SyncTaskConfig> getAllTasks() {
        return new ArrayList<>(taskCache.values());
    }

    @Override
    public List<SyncTaskConfig> getEnabledTasks() {
        return taskCache.values().stream()
                .filter(t -> Boolean.TRUE.equals(t.getEnabled()))
                .collect(Collectors.toList());
    }

    @Override
    public boolean deleteTask(String taskId) {
        SyncTaskConfig removed = taskCache.remove(taskId);
        if (removed != null) {
            deleteFromRedis(Constants.REDIS_KEY_PREFIX_TASK + taskId);
            logger.info("Deleted task: {}", taskId);
            return true;
        }
        return false;
    }

    @Override
    public SyncTaskConfig toggleTask(String taskId, boolean enabled) {
        SyncTaskConfig config = taskCache.get(taskId);
        if (config == null) {
            throw new NoSuchElementException("Task not found: " + taskId);
        }
        config.setEnabled(enabled);
        saveToRedis(Constants.REDIS_KEY_PREFIX_TASK + config.getTaskId(), config);
        logger.info("Toggled task: {} -> {}", taskId, enabled);
        return config;
    }

    @Override
    public void testConnection(DataSourceConfig config) throws Exception {
        String type = config.getSourceType();
        String host = config.getHost();
        Integer port = config.getPort();
        String database = config.getDatabase();
        String user = config.getUser();
        String password = config.getPassword();

        if (Constants.DATA_SOURCE_TYPE_REDIS.equals(type)) {
            logger.info("Redis connection test: {}:{}", host, port);
            return;
        }

        String url;
        if (Constants.DATA_SOURCE_TYPE_MYSQL.equals(type)) {
            url = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC",
                    host, port != null ? port : 3306, database);
        } else if (Constants.DATA_SOURCE_TYPE_POSTGRESQL.equals(type)) {
            url = String.format("jdbc:postgresql://%s:%d/%s",
                    host, port != null ? port : 5432, database);
        } else if (Constants.DATA_SOURCE_TYPE_ORACLE.equals(type)) {
            url = String.format("jdbc:oracle:thin:@%s:%d:%s",
                    host, port != null ? port : 1521, database);
        } else {
            throw new IllegalArgumentException("Unsupported data source type: " + type);
        }

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            if (conn != null && conn.isValid(5)) {
                logger.info("Connection test successful for: {}", config.getSourceId());
            } else {
                throw new Exception("Connection failed: invalid connection");
            }
        } catch (Exception e) {
            logger.error("Connection test failed for: {}", config.getSourceId(), e);
            throw e;
        }
    }

    @Override
    public ConflictStrategyConfig saveConflictStrategy(ConflictStrategyConfig config) {
        if (config.getConfigId() == null || config.getConfigId().isEmpty()) {
            config.setConfigId("strategy_" + UUID.randomUUID().toString().substring(0, 8));
        }
        strategyCache.put(config.getConfigId(), config);
        saveToRedis(REDIS_KEY_PREFIX_STRATEGY + config.getConfigId(), config);
        logger.info("Saved conflict strategy: {} for task: {}", config.getConfigId(), config.getTaskId());
        return config;
    }

    @Override
    public Optional<ConflictStrategyConfig> getConflictStrategy(String configId) {
        return Optional.ofNullable(strategyCache.get(configId));
    }

    @Override
    public Optional<ConflictStrategyConfig> getConflictStrategyByTask(String taskId) {
        return strategyCache.values().stream()
                .filter(s -> taskId.equals(s.getTaskId()))
                .findFirst();
    }

    @Override
    public List<ConflictStrategyConfig> getAllConflictStrategies() {
        return new ArrayList<>(strategyCache.values());
    }

    @Override
    public boolean deleteConflictStrategy(String configId) {
        ConflictStrategyConfig removed = strategyCache.remove(configId);
        if (removed != null) {
            deleteFromRedis(REDIS_KEY_PREFIX_STRATEGY + configId);
            logger.info("Deleted conflict strategy: {}", configId);
            return true;
        }
        return false;
    }

    @Override
    public ConflictStrategyConfig getOrCreateConflictStrategy(String taskId, String defaultStrategy) {
        Optional<ConflictStrategyConfig> existing = getConflictStrategyByTask(taskId);
        if (existing.isPresent()) {
            return existing.get();
        }

        ConflictStrategyConfig config = new ConflictStrategyConfig();
        config.setTaskId(taskId);
        config.setDefaultStrategy(defaultStrategy != null ? defaultStrategy : Constants.CONFLICT_STRATEGY_SOURCE_PRIORITY);
        config.setEnabled(true);
        return saveConflictStrategy(config);
    }

    @Override
    public void loadAllStrategiesFromPersistence() {
        try {
            Set<String> keys = redisTemplate.keys(REDIS_KEY_PREFIX_STRATEGY + "*");
            if (keys != null) {
                for (String key : keys) {
                    try {
                        String value = redisTemplate.opsForValue().get(key);
                        if (value != null) {
                            ConflictStrategyConfig config = objectMapper.readValue(value, ConflictStrategyConfig.class);
                            strategyCache.put(config.getConfigId(), config);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to load strategy from Redis: {}", key, e);
                    }
                }
            }
            logger.info("Loaded {} conflict strategies from persistence", strategyCache.size());
        } catch (Exception e) {
            logger.warn("Failed to load strategies from Redis", e);
        }
    }
}
