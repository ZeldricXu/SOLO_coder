package com.datasync.service.conflict.impl;

import com.datasync.common.Constants;
import com.datasync.model.ConflictRecord;
import com.datasync.model.ConflictStrategyConfig;
import com.datasync.service.conflict.ConflictDetector;
import com.datasync.service.conflict.ConflictHandler;
import com.datasync.service.strategy.ConflictStrategyManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ConflictHandlerImpl implements ConflictHandler {

    private static final Logger logger = LoggerFactory.getLogger(ConflictHandlerImpl.class);

    private final Map<String, ConflictRecord> conflictCache = new ConcurrentHashMap<>();

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConflictDetector conflictDetector;

    @Autowired(required = false)
    private ConflictStrategyManager strategyManager;

    @PostConstruct
    public void init() {
        loadAllConflictsFromPersistence();
    }

    @Override
    public void loadAllConflictsFromPersistence() {
        try {
            Set<String> keys = redisTemplate.keys(Constants.REDIS_KEY_PREFIX_CONFLICT + "*");
            if (keys != null && !keys.isEmpty()) {
                int loaded = 0;
                for (String key : keys) {
                    try {
                        String json = redisTemplate.opsForValue().get(key);
                        if (json != null) {
                            ConflictRecord conflict = objectMapper.readValue(json, ConflictRecord.class);
                            conflictCache.put(conflict.getConflictId(), conflict);
                            loaded++;
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to load conflict from Redis: {}", key, e);
                    }
                }
                logger.info("Loaded {} conflicts from persistence", loaded);
            }
        } catch (Exception e) {
            logger.warn("Failed to load conflicts from Redis", e);
        }
    }

    @Override
    public ConflictRecord handleConflictWithAutoStrategy(ConflictRecord conflict, String defaultStrategy) {
        String autoStrategy;

        if (strategyManager != null) {
            autoStrategy = strategyManager.resolveStrategy(
                    conflict,
                    null,
                    defaultStrategy
            );
            logger.info("Strategy '{}' resolved from strategy manager for conflict {} (type: {})",
                    autoStrategy, conflict.getConflictId(), conflict.getConflictType());
        } else {
            autoStrategy = conflictDetector.selectStrategyByConflictType(
                    conflict.getConflictType(),
                    conflict.getPriority(),
                    defaultStrategy
            );
            logger.info("Auto-selected strategy '{}' for conflict {} (type: {}, priority: {})",
                    autoStrategy, conflict.getConflictId(),
                    conflict.getConflictType(), conflict.getPriority());
        }

        return handleConflict(conflict, autoStrategy);
    }

    @Override
    public ConflictRecord handleConflictWithConfig(ConflictRecord conflict, ConflictStrategyConfig strategyConfig) {
        String resolvedStrategy;

        if (strategyManager != null && strategyConfig != null && strategyConfig.isEnabled()) {
            resolvedStrategy = strategyManager.resolveStrategy(
                    conflict,
                    strategyConfig,
                    strategyConfig.getDefaultStrategy()
            );
            logger.info("Strategy '{}' resolved from config '{}' for conflict {} (type: {})",
                    resolvedStrategy, strategyConfig.getConfigId(),
                    conflict.getConflictId(), conflict.getConflictType());
        } else {
            String defaultStrategy = strategyConfig != null
                    ? strategyConfig.getDefaultStrategy()
                    : Constants.CONFLICT_STRATEGY_SOURCE_PRIORITY;
            resolvedStrategy = conflictDetector.selectStrategyByConflictType(
                    conflict.getConflictType(),
                    conflict.getPriority(),
                    defaultStrategy
            );
        }

        return handleConflict(conflict, resolvedStrategy);
    }

    @Override
    public ConflictRecord handleConflict(ConflictRecord conflict, String strategy) {
        logger.info("Handling conflict {} with strategy: {}", conflict.getConflictId(), strategy);

        try {
            switch (strategy) {
                case Constants.CONFLICT_STRATEGY_SOURCE_PRIORITY:
                    handleSourcePriority(conflict);
                    break;
                case Constants.CONFLICT_STRATEGY_TARGET_PRIORITY:
                    handleTargetPriority(conflict);
                    break;
                case Constants.CONFLICT_STRATEGY_MERGE:
                    handleMerge(conflict);
                    break;
                case Constants.CONFLICT_STRATEGY_MANUAL:
                    handleManual(conflict);
                    break;
                default:
                    handleManual(conflict);
            }
        } catch (Exception e) {
            logger.error("Error handling conflict: {}", conflict.getConflictId(), e);
            conflict.setErrorMessage(e.getMessage());
            conflict.setStatus(Constants.CONFLICT_STATUS_FAILED);
        }

        saveConflict(conflict);
        return conflict;
    }

    private void handleSourcePriority(ConflictRecord conflict) {
        logger.info("Handling conflict with source priority: {}", conflict.getConflictId());
        conflict.markResolved(Constants.CONFLICT_STRATEGY_SOURCE_PRIORITY);
        conflict.setStatus(Constants.CONFLICT_STATUS_AUTO_RESOLVED);
    }

    private void handleTargetPriority(ConflictRecord conflict) {
        logger.info("Handling conflict with target priority: {}", conflict.getConflictId());
        conflict.markResolved(Constants.CONFLICT_STRATEGY_TARGET_PRIORITY);
        conflict.setStatus(Constants.CONFLICT_STATUS_AUTO_RESOLVED);
    }

    private void handleMerge(ConflictRecord conflict) {
        logger.info("Handling conflict with merge strategy: {}", conflict.getConflictId());
        try {
            if (Constants.CONFLICT_TYPE_STRUCTURE.equals(conflict.getConflictType())) {
                if (conflict.getRemovedFields() != null && !conflict.getRemovedFields().isEmpty()) {
                    logger.warn("Merge strategy not recommended for structure conflicts with removed fields. " +
                            "Conflict: {}", conflict.getConflictId());
                }
            }

            Map<String, Object> merged = mergeMaps(conflict.getSourceValue(), conflict.getTargetValue());
            conflict.markResolved(Constants.CONFLICT_STRATEGY_MERGE);
            conflict.setStatus(Constants.CONFLICT_STATUS_AUTO_RESOLVED);
            conflict.setSourceValue(merged);
            logger.info("Merge successful for conflict: {}", conflict.getConflictId());
        } catch (Exception e) {
            logger.warn("Merge failed, falling back to manual: {}", conflict.getConflictId(), e);
            conflict.setErrorMessage("Merge failed: " + e.getMessage());
            conflict.markManualRequired();
        }
    }

    private void handleManual(ConflictRecord conflict) {
        logger.info("Conflict marked for manual resolution: {} (type: {}, priority: {})",
                conflict.getConflictId(), conflict.getConflictType(), conflict.getPriority());
        conflict.markManualRequired();
    }

    private Map<String, Object> mergeMaps(Map<String, Object> source, Map<String, Object> target) {
        if (source == null && target == null) {
            return new HashMap<>();
        }
        if (source == null) {
            return new HashMap<>(target);
        }
        if (target == null) {
            return new HashMap<>(source);
        }

        Map<String, Object> merged = new HashMap<>(target);
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object sourceValue = entry.getValue();

            if (!merged.containsKey(key)) {
                merged.put(key, sourceValue);
            } else {
                Object targetValue = merged.get(key);
                if (sourceValue instanceof Map && targetValue instanceof Map) {
                    merged.put(key, mergeMaps((Map<String, Object>) sourceValue, (Map<String, Object>) targetValue));
                } else {
                    merged.put(key, sourceValue);
                }
            }
        }
        return merged;
    }

    @Override
    public Optional<ConflictRecord> getConflict(String conflictId) {
        ConflictRecord cached = conflictCache.get(conflictId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            String json = redisTemplate.opsForValue().get(Constants.REDIS_KEY_PREFIX_CONFLICT + conflictId);
            if (json != null) {
                ConflictRecord conflict = objectMapper.readValue(json, ConflictRecord.class);
                conflictCache.put(conflictId, conflict);
                return Optional.of(conflict);
            }
        } catch (Exception e) {
            logger.warn("Failed to get conflict from Redis: {}", conflictId, e);
        }
        return Optional.empty();
    }

    @Override
    public List<ConflictRecord> getConflictsBySyncId(String syncId) {
        return conflictCache.values().stream()
                .filter(c -> syncId.equals(c.getSyncId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<ConflictRecord> getConflictsByTaskId(String taskId) {
        return conflictCache.values().stream()
                .filter(c -> taskId.equals(c.getTaskId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<ConflictRecord> getPendingConflicts() {
        return conflictCache.values().stream()
                .filter(c -> Constants.CONFLICT_STATUS_PENDING.equals(c.getStatus()))
                .collect(Collectors.toList());
    }

    @Override
    public List<ConflictRecord> getConflictsByPriority(int priority) {
        return conflictCache.values().stream()
                .filter(c -> c.getPriority() != null && c.getPriority() == priority)
                .sorted(Comparator.comparing(ConflictRecord::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<ConflictRecord> getConflictsByType(String conflictType) {
        return conflictCache.values().stream()
                .filter(c -> conflictType.equals(c.getConflictType()))
                .sorted(Comparator.comparing(ConflictRecord::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<ConflictRecord> getAllConflicts() {
        return conflictCache.values().stream()
                .sorted(Comparator.comparing(ConflictRecord::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<ConflictRecord> getConflictsSortedByPriority() {
        return conflictCache.values().stream()
                .sorted(Comparator.comparing((ConflictRecord c) ->
                        c.getPriority() != null ? c.getPriority() : Integer.MAX_VALUE)
                        .thenComparing(ConflictRecord::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public ConflictRecord resolveManualConflict(String conflictId, Map<String, Object> resolutionValue) {
        ConflictRecord conflict = conflictCache.get(conflictId);
        if (conflict == null) {
            Optional<ConflictRecord> opt = getConflict(conflictId);
            if (opt.isPresent()) {
                conflict = opt.get();
            } else {
                throw new NoSuchElementException("Conflict not found: " + conflictId);
            }
        }
        conflict.setSourceValue(resolutionValue);
        conflict.markResolved("manual_resolution");
        saveConflict(conflict);
        logger.info("Manually resolved conflict: {}", conflictId);
        return conflict;
    }

    @Override
    public void saveConflict(ConflictRecord conflict) {
        conflictCache.put(conflict.getConflictId(), conflict);
        try {
            String json = objectMapper.writeValueAsString(conflict);
            redisTemplate.opsForValue().set(Constants.REDIS_KEY_PREFIX_CONFLICT + conflict.getConflictId(), json);
            logger.debug("Persisted conflict: {}", conflict.getConflictId());
        } catch (Exception e) {
            logger.warn("Failed to save conflict to Redis: {}", conflict.getConflictId(), e);
        }
    }

    @Override
    public int getConflictCountByStatus(String status) {
        return (int) conflictCache.values().stream()
                .filter(c -> status.equals(c.getStatus()))
                .count();
    }

    @Override
    public int getConflictCountByType(String conflictType) {
        return (int) conflictCache.values().stream()
                .filter(c -> conflictType.equals(c.getConflictType()))
                .count();
    }

    @Override
    public int getConflictCountByPriority(int priority) {
        return (int) conflictCache.values().stream()
                .filter(c -> c.getPriority() != null && c.getPriority() == priority)
                .count();
    }
}
