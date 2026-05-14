package com.datasync.service.strategy.impl;

import com.datasync.common.Constants;
import com.datasync.model.ConflictRecord;
import com.datasync.model.ConflictStrategyConfig;
import com.datasync.model.ConflictStrategyConfig.ConflictStrategyMapping;
import com.datasync.model.ConflictStrategyConfig.CustomStrategyCondition;
import com.datasync.model.ConflictStrategyConfig.PriorityOverrideRule;
import com.datasync.service.strategy.ConflictStrategyManager;
import com.datasync.service.strategy.StrategyExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ConflictStrategyManagerImpl implements ConflictStrategyManager {

    private static final Logger logger = LoggerFactory.getLogger(ConflictStrategyManagerImpl.class);

    public static final String REDIS_KEY_PREFIX_STRATEGY = "conflict_strategy:";

    private final Map<String, ConflictStrategyConfig> configCache = new ConcurrentHashMap<>();
    private final Map<String, ConflictStrategyConfig> taskToConfig = new ConcurrentHashMap<>();
    private final Map<String, List<StrategyExtension>> extensions = new ConcurrentHashMap<>();

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        loadAllConfigsFromPersistence();
        logger.info("ConflictStrategyManager initialized with {} configs", configCache.size());
    }

    @Override
    public void loadAllConfigsFromPersistence() {
        try {
            Set<String> keys = redisTemplate.keys(REDIS_KEY_PREFIX_STRATEGY + "*");
            if (keys != null && !keys.isEmpty()) {
                int loaded = 0;
                for (String key : keys) {
                    try {
                        String json = redisTemplate.opsForValue().get(key);
                        if (json != null) {
                            ConflictStrategyConfig config = objectMapper.readValue(json, ConflictStrategyConfig.class);
                            configCache.put(config.getConfigId(), config);
                            if (config.getTaskId() != null) {
                                taskToConfig.put(config.getTaskId(), config);
                            }
                            loaded++;
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to load strategy config from Redis: {}", key, e);
                    }
                }
                logger.info("Loaded {} conflict strategy configs from persistence", loaded);
            }
        } catch (Exception e) {
            logger.warn("Failed to load strategy configs from Redis", e);
        }
    }

    @Override
    public ConflictStrategyConfig saveConfig(ConflictStrategyConfig config) {
        if (config.getConfigId() == null || config.getConfigId().isEmpty()) {
            config.setConfigId("strat_" + UUID.randomUUID().toString().substring(0, 8));
        }
        config.setUpdatedAt(new Date().toInstant());

        configCache.put(config.getConfigId(), config);
        if (config.getTaskId() != null) {
            taskToConfig.put(config.getTaskId(), config);
        }

        persistConfig(config);
        logger.info("Saved conflict strategy config: {} for task: {}", config.getConfigId(), config.getTaskId());
        return config;
    }

    @Override
    public Optional<ConflictStrategyConfig> getConfig(String configId) {
        ConflictStrategyConfig cached = configCache.get(configId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            String json = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX_STRATEGY + configId);
            if (json != null) {
                ConflictStrategyConfig config = objectMapper.readValue(json, ConflictStrategyConfig.class);
                configCache.put(configId, config);
                return Optional.of(config);
            }
        } catch (Exception e) {
            logger.warn("Failed to get strategy config from Redis: {}", configId, e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<ConflictStrategyConfig> getConfigByTask(String taskId) {
        ConflictStrategyConfig cached = taskToConfig.get(taskId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return Optional.empty();
    }

    @Override
    public List<ConflictStrategyConfig> getAllConfigs() {
        return new ArrayList<>(configCache.values());
    }

    @Override
    public boolean deleteConfig(String configId) {
        ConflictStrategyConfig removed = configCache.remove(configId);
        if (removed != null) {
            if (removed.getTaskId() != null) {
                taskToConfig.remove(removed.getTaskId());
            }
            try {
                redisTemplate.delete(REDIS_KEY_PREFIX_STRATEGY + configId);
            } catch (Exception e) {
                logger.warn("Failed to delete strategy config from Redis: {}", configId, e);
            }
            logger.info("Deleted conflict strategy config: {}", configId);
            return true;
        }
        return false;
    }

    @Override
    public ConflictStrategyConfig toggleConfig(String configId, boolean enabled) {
        ConflictStrategyConfig config = configCache.get(configId);
        if (config == null) {
            throw new NoSuchElementException("Strategy config not found: " + configId);
        }
        config.setEnabled(enabled);
        config.setUpdatedAt(new Date().toInstant());
        persistConfig(config);
        logger.info("Toggled strategy config: {} -> {}", configId, enabled);
        return config;
    }

    @Override
    public String resolveStrategy(ConflictRecord conflict, String taskId) {
        Optional<ConflictStrategyConfig> configOpt = getConfigByTask(taskId);
        if (configOpt.isPresent()) {
            return resolveStrategy(conflict, configOpt.get());
        }
        ConflictStrategyConfig defaultConfig = createDefaultConfig(taskId, Constants.CONFLICT_STRATEGY_SOURCE_PRIORITY);
        return resolveStrategy(conflict, defaultConfig);
    }

    @Override
    public String resolveStrategy(ConflictRecord conflict, ConflictStrategyConfig config) {
        String defaultStrategy = config.getDefaultStrategy() != null
                ? config.getDefaultStrategy()
                : Constants.CONFLICT_STRATEGY_SOURCE_PRIORITY;
        return resolveStrategy(conflict, config, defaultStrategy);
    }

    @Override
    public String resolveStrategy(ConflictRecord conflict, ConflictStrategyConfig config, String defaultStrategy) {
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            logger.debug("Strategy config disabled, using default strategy: {}", defaultStrategy);
            return defaultStrategy;
        }

        String conflictType = conflict.getConflictType();
        Integer priority = conflict.getPriority();

        if (config.getCustomConditions() != null) {
            List<CustomStrategyCondition> sortedConditions = config.getCustomConditions().stream()
                    .filter(c -> Boolean.TRUE.equals(c.getEnabled()))
                    .sorted(Comparator.comparing(CustomStrategyCondition::getOrder, Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList());

            for (CustomStrategyCondition condition : sortedConditions) {
                if (evaluateCondition(condition, conflict)) {
                    logger.info("Custom condition matched: {} -> strategy: {}", condition.getName(), condition.getStrategy());
                    return condition.getStrategy();
                }
            }
        }

        List<StrategyExtension> typeExtensions = extensions.get(conflictType);
        if (typeExtensions != null) {
            for (StrategyExtension ext : typeExtensions) {
                if (ext.appliesTo(conflict)) {
                    String strategy = ext.resolveStrategy(conflict, defaultStrategy);
                    logger.info("Strategy extension applied for type {}: {}", conflictType, strategy);
                    return strategy;
                }
            }
        }

        if (config.getStrategyMappings() != null) {
            ConflictStrategyMapping mapping = config.getStrategyMappings().get(conflictType);
            if (mapping != null && mapping.matches(priority)) {
                logger.info("Strategy mapping found for type {}: {}", conflictType, mapping.getStrategy());
                return mapping.getStrategy();
            }

            if (conflictType != null && conflictType.endsWith("_conflict")) {
                String baseType = conflictType.substring(0, conflictType.length() - 9);
                mapping = config.getStrategyMappings().entrySet().stream()
                        .filter(e -> e.getKey().startsWith(baseType))
                        .map(Map.Entry::getValue)
                        .filter(m -> m.matches(priority))
                        .findFirst()
                        .orElse(null);
                if (mapping != null) {
                    return mapping.getStrategy();
                }
            }
        }

        logger.debug("No matching strategy mapping, using default: {}", defaultStrategy);
        return defaultStrategy;
    }

    private boolean evaluateCondition(CustomStrategyCondition condition, ConflictRecord conflict) {
        String expression = condition.getExpression();
        if (expression == null || expression.isEmpty()) {
            return false;
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("conflictType", conflict.getConflictType());
        context.put("priority", conflict.getPriority());
        context.put("dataKey", conflict.getDataKey());
        context.put("sourceVersion", conflict.getSourceVersion());
        context.put("targetVersion", conflict.getTargetVersion());

        if (conflict.getSourceFields() != null) {
            context.put("sourceFieldCount", conflict.getSourceFields().size());
        }
        if (conflict.getAddedFields() != null) {
            context.put("addedFieldCount", conflict.getAddedFields().size());
            context.put("hasAddedFields", !conflict.getAddedFields().isEmpty());
        }
        if (conflict.getRemovedFields() != null) {
            context.put("removedFieldCount", conflict.getRemovedFields().size());
            context.put("hasRemovedFields", !conflict.getRemovedFields().isEmpty());
        }
        if (conflict.getTypeMismatchFields() != null) {
            context.put("typeMismatchCount", conflict.getTypeMismatchFields().size());
            context.put("hasTypeMismatch", !conflict.getTypeMismatchFields().isEmpty());
        }

        return evaluateSimpleExpression(expression, context);
    }

    private boolean evaluateSimpleExpression(String expression, Map<String, Object> context) {
        try {
            String trimmed = expression.trim();

            if (trimmed.startsWith("!")) {
                return !evaluateSimpleExpression(trimmed.substring(1), context);
            }

            if (trimmed.contains("&&")) {
                String[] parts = trimmed.split("&&", 2);
                return evaluateSimpleExpression(parts[0].trim(), context) &&
                       evaluateSimpleExpression(parts[1].trim(), context);
            }

            if (trimmed.contains("||")) {
                String[] parts = trimmed.split("\\|\\|", 2);
                return evaluateSimpleExpression(parts[0].trim(), context) ||
                       evaluateSimpleExpression(parts[1].trim(), context);
            }

            if (trimmed.contains("==")) {
                String[] parts = trimmed.split("==", 2);
                Object left = resolveValue(parts[0].trim(), context);
                Object right = resolveValue(parts[1].trim(), context);
                return Objects.equals(left, right);
            }

            if (trimmed.contains("!=")) {
                String[] parts = trimmed.split("!=", 2);
                Object left = resolveValue(parts[0].trim(), context);
                Object right = resolveValue(parts[1].trim(), context);
                return !Objects.equals(left, right);
            }

            if (trimmed.contains(">=")) {
                String[] parts = trimmed.split(">=", 2);
                Double left = toDouble(resolveValue(parts[0].trim(), context));
                Double right = toDouble(resolveValue(parts[1].trim(), context));
                return left != null && right != null && left >= right;
            }

            if (trimmed.contains("<=")) {
                String[] parts = trimmed.split("<=", 2);
                Double left = toDouble(resolveValue(parts[0].trim(), context));
                Double right = toDouble(resolveValue(parts[1].trim(), context));
                return left != null && right != null && left <= right;
            }

            if (trimmed.contains(">")) {
                String[] parts = trimmed.split(">", 2);
                Double left = toDouble(resolveValue(parts[0].trim(), context));
                Double right = toDouble(resolveValue(parts[1].trim(), context));
                return left != null && right != null && left > right;
            }

            if (trimmed.contains("<")) {
                String[] parts = trimmed.split("<", 2);
                Double left = toDouble(resolveValue(parts[0].trim(), context));
                Double right = toDouble(resolveValue(parts[1].trim(), context));
                return left != null && right != null && left < right;
            }

            if (trimmed.contains("contains")) {
                String[] parts = trimmed.split("contains", 2);
                String left = String.valueOf(resolveValue(parts[0].trim(), context));
                String right = stripQuotes(parts[1].trim());
                return left.contains(right);
            }

            if (trimmed.contains("matches")) {
                String[] parts = trimmed.split("matches", 2);
                String left = String.valueOf(resolveValue(parts[0].trim(), context));
                String pattern = stripQuotes(parts[1].trim());
                return Pattern.matches(pattern, left);
            }

            Object value = context.get(trimmed);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }

            return false;
        } catch (Exception e) {
            logger.warn("Error evaluating expression: {}", expression, e);
            return false;
        }
    }

    private Object resolveValue(String key, Map<String, Object> context) {
        String trimmed = key.trim();
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) ||
            (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return false;
        }
        if ("null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return context.get(trimmed);
        }
    }

    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String stripQuotes(String value) {
        String trimmed = value.trim();
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) ||
            (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    @Override
    public ConflictStrategyConfig createDefaultConfig(String taskId, String defaultStrategy) {
        ConflictStrategyConfig config = new ConflictStrategyConfig();
        config.setTaskId(taskId);
        config.setName("Default Strategy for " + taskId);
        config.setDescription("Auto-generated default strategy configuration");
        config.setDefaultStrategy(defaultStrategy);
        config.setEnabled(true);

        config.addMapping(Constants.CONFLICT_TYPE_VERSION,
                Constants.CONFLICT_STRATEGY_SOURCE_PRIORITY,
                null, Constants.CONFLICT_PRIORITY_MEDIUM);

        config.addMapping(Constants.CONFLICT_TYPE_CONTENT,
                Constants.CONFLICT_STRATEGY_SOURCE_PRIORITY,
                null, Constants.CONFLICT_PRIORITY_LOW);

        config.addMapping(Constants.CONFLICT_TYPE_STRUCTURE,
                Constants.CONFLICT_STRATEGY_MANUAL,
                Constants.CONFLICT_PRIORITY_CRITICAL, Constants.CONFLICT_PRIORITY_CRITICAL);

        config.addMapping(Constants.CONFLICT_TYPE_TYPE_MISMATCH,
                Constants.CONFLICT_STRATEGY_MANUAL,
                Constants.CONFLICT_PRIORITY_CRITICAL, Constants.CONFLICT_PRIORITY_CRITICAL);

        config.addMapping(Constants.CONFLICT_TYPE_MIXED,
                Constants.CONFLICT_STRATEGY_MANUAL,
                Constants.CONFLICT_PRIORITY_HIGH, Constants.CONFLICT_PRIORITY_HIGH);

        return saveConfig(config);
    }

    @Override
    public void registerStrategyExtension(String conflictType, StrategyExtension extension) {
        extensions.computeIfAbsent(conflictType, k -> new ArrayList<>()).add(extension);
        logger.info("Registered strategy extension for conflict type: {}", conflictType);
    }

    private void persistConfig(ConflictStrategyConfig config) {
        try {
            String json = objectMapper.writeValueAsString(config);
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX_STRATEGY + config.getConfigId(), json);
        } catch (Exception e) {
            logger.warn("Failed to persist strategy config to Redis: {}", config.getConfigId(), e);
        }
    }
}
