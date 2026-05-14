package com.datasync.service.config;

import com.datasync.model.ConflictStrategyConfig;
import com.datasync.model.DataSourceConfig;
import com.datasync.model.SyncTaskConfig;

import java.util.List;
import java.util.Optional;

public interface ConfigManager {

    DataSourceConfig saveDataSource(DataSourceConfig config);

    Optional<DataSourceConfig> getDataSource(String sourceId);

    List<DataSourceConfig> getAllDataSources();

    boolean deleteDataSource(String sourceId);

    DataSourceConfig updateDataSourceStatus(String sourceId, String status);

    SyncTaskConfig saveTask(SyncTaskConfig config);

    Optional<SyncTaskConfig> getTask(String taskId);

    List<SyncTaskConfig> getAllTasks();

    List<SyncTaskConfig> getEnabledTasks();

    boolean deleteTask(String taskId);

    SyncTaskConfig toggleTask(String taskId, boolean enabled);

    void testConnection(DataSourceConfig config) throws Exception;

    ConflictStrategyConfig saveConflictStrategy(ConflictStrategyConfig config);

    Optional<ConflictStrategyConfig> getConflictStrategy(String configId);

    Optional<ConflictStrategyConfig> getConflictStrategyByTask(String taskId);

    List<ConflictStrategyConfig> getAllConflictStrategies();

    boolean deleteConflictStrategy(String configId);

    ConflictStrategyConfig getOrCreateConflictStrategy(String taskId, String defaultStrategy);

    void loadAllStrategiesFromPersistence();
}
