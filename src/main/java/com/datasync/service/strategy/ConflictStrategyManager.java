package com.datasync.service.strategy;

import com.datasync.model.ConflictRecord;
import com.datasync.model.ConflictStrategyConfig;

import java.util.List;
import java.util.Optional;

public interface ConflictStrategyManager {

    ConflictStrategyConfig saveConfig(ConflictStrategyConfig config);

    Optional<ConflictStrategyConfig> getConfig(String configId);

    Optional<ConflictStrategyConfig> getConfigByTask(String taskId);

    List<ConflictStrategyConfig> getAllConfigs();

    boolean deleteConfig(String configId);

    ConflictStrategyConfig toggleConfig(String configId, boolean enabled);

    String resolveStrategy(ConflictRecord conflict, String taskId);

    String resolveStrategy(ConflictRecord conflict, ConflictStrategyConfig config);

    String resolveStrategy(ConflictRecord conflict, ConflictStrategyConfig config, String defaultStrategy);

    ConflictStrategyConfig createDefaultConfig(String taskId, String defaultStrategy);

    void loadAllConfigsFromPersistence();

    void registerStrategyExtension(String conflictType, StrategyExtension extension);
}
