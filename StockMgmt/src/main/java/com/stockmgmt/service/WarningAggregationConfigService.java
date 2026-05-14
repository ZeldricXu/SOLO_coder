package com.stockmgmt.service;

import com.stockmgmt.entity.WarningAggregationConfig;
import com.stockmgmt.enums.WarningLevel;
import com.stockmgmt.enums.WarningType;
import com.stockmgmt.repository.WarningAggregationConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WarningAggregationConfigService {

    private static final Logger logger = LoggerFactory.getLogger(WarningAggregationConfigService.class);

    @Autowired
    private WarningAggregationConfigRepository configRepository;

    @Value("${stock.warning.aggregation.default-window-seconds:300}")
    private int defaultWindowSeconds;

    @Value("${stock.warning.aggregation.default-max-notifications:1}")
    private int defaultMaxNotifications;

    @Value("${stock.warning.aggregation.default-cooldown-seconds:300}")
    private int defaultCooldownSeconds;

    private final Map<String, WarningAggregationConfig> configCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadConfigs();
    }

    public void loadConfigs() {
        logger.info("加载预警聚合配置...");
        configCache.clear();
        configRepository.findByEnabledTrue().forEach(config -> {
            String key = buildCacheKey(
                    config.getWarningLevel(),
                    config.getWarningType(),
                    config.getProductId(),
                    config.getWarehouseId());
            configCache.put(key, config);
            logger.debug("加载配置: level={}, type={}, product={}, warehouse={}, window={}s",
                    config.getWarningLevel(), config.getWarningType(),
                    config.getProductId(), config.getWarehouseId(),
                    config.getAggregationWindowSeconds());
        });
        logger.info("预警聚合配置加载完成，共加载 {} 条配置", configCache.size());
    }

    public WarningAggregationConfig getConfig(WarningLevel level, WarningType type,
                                                String productId, String warehouseId) {
        String levelCode = level != null ? level.getCode() : null;
        String typeCode = type != null ? type.getCode() : null;

        logger.debug("获取预警聚合配置: level={}, type={}, product={}, warehouse={}",
                levelCode, typeCode, productId, warehouseId);

        String specificKey = buildCacheKey(levelCode, typeCode, productId, warehouseId);
        WarningAggregationConfig config = configCache.get(specificKey);
        if (config != null) {
            logger.debug("命中具体配置: window={}s", config.getAggregationWindowSeconds());
            return config;
        }

        String productKey = buildCacheKey(levelCode, typeCode, productId, null);
        config = configCache.get(productKey);
        if (config != null) {
            logger.debug("命中商品级配置: window={}s", config.getAggregationWindowSeconds());
            return config;
        }

        String warehouseKey = buildCacheKey(levelCode, typeCode, null, warehouseId);
        config = configCache.get(warehouseKey);
        if (config != null) {
            logger.debug("命中仓库级配置: window={}s", config.getAggregationWindowSeconds());
            return config;
        }

        String levelTypeKey = buildCacheKey(levelCode, typeCode, null, null);
        config = configCache.get(levelTypeKey);
        if (config != null) {
            logger.debug("命中级别+类型默认配置: window={}s", config.getAggregationWindowSeconds());
            return config;
        }

        logger.debug("使用默认配置: window={}s", getDefaultWindowSeconds(level));
        return buildDefaultConfig(level, type);
    }

    public int getAggregationWindowSeconds(WarningLevel level, WarningType type,
                                           String productId, String warehouseId) {
        WarningAggregationConfig config = getConfig(level, type, productId, warehouseId);
        return config.getAggregationWindowSeconds();
    }

    public int getMaxNotificationsPerWindow(WarningLevel level, WarningType type,
                                            String productId, String warehouseId) {
        WarningAggregationConfig config = getConfig(level, type, productId, warehouseId);
        return config.getMaxNotificationsPerWindow();
    }

    public int getNotificationCooldownSeconds(WarningLevel level, WarningType type,
                                              String productId, String warehouseId) {
        WarningAggregationConfig config = getConfig(level, type, productId, warehouseId);
        return config.getNotificationCooldownSeconds();
    }

    public boolean isUrgentLevel(WarningLevel level) {
        return level == WarningLevel.HIGH;
    }

    public WarningAggregationConfig saveConfig(WarningAggregationConfig config) {
        WarningAggregationConfig saved = configRepository.save(config);
        loadConfigs();
        logger.info("预警聚合配置已保存: id={}, level={}, type={}, window={}s",
                saved.getId(), saved.getWarningLevel(), saved.getWarningType(),
                saved.getAggregationWindowSeconds());
        return saved;
    }

    public void deleteConfig(Long id) {
        configRepository.deleteById(id);
        loadConfigs();
        logger.info("预警聚合配置已删除: id={}", id);
    }

    public Optional<WarningAggregationConfig> getConfigById(Long id) {
        return configRepository.findById(id);
    }

    private String buildCacheKey(String level, String type, String productId, String warehouseId) {
        return String.format("%s|%s|%s|%s",
                level != null ? level : "NULL",
                type != null ? type : "NULL",
                productId != null ? productId : "NULL",
                warehouseId != null ? warehouseId : "NULL");
    }

    private WarningAggregationConfig buildDefaultConfig(WarningLevel level, WarningType type) {
        int windowSeconds = getDefaultWindowSeconds(level);
        int maxNotifications = getDefaultMaxNotifications(level);
        int cooldownSeconds = getDefaultCooldownSeconds(level);

        return WarningAggregationConfig.builder()
                .warningLevel(level != null ? level.getCode() : WarningLevel.MEDIUM.getCode())
                .warningType(type != null ? type.getCode() : WarningType.LOW_STOCK.getCode())
                .aggregationWindowSeconds(windowSeconds)
                .maxNotificationsPerWindow(maxNotifications)
                .notificationCooldownSeconds(cooldownSeconds)
                .enabled(true)
                .build();
    }

    private int getDefaultWindowSeconds(WarningLevel level) {
        if (level == null) return defaultWindowSeconds;
        switch (level) {
            case HIGH: return Math.min(defaultWindowSeconds, 60);
            case MEDIUM: return defaultWindowSeconds;
            case LOW: return Math.max(defaultWindowSeconds, 600);
            default: return defaultWindowSeconds;
        }
    }

    private int getDefaultMaxNotifications(WarningLevel level) {
        if (level == null) return defaultMaxNotifications;
        switch (level) {
            case HIGH: return 3;
            case MEDIUM: return 2;
            case LOW: return 1;
            default: return defaultMaxNotifications;
        }
    }

    private int getDefaultCooldownSeconds(WarningLevel level) {
        if (level == null) return defaultCooldownSeconds;
        switch (level) {
            case HIGH: return Math.min(defaultCooldownSeconds, 120);
            case MEDIUM: return defaultCooldownSeconds;
            case LOW: return Math.max(defaultCooldownSeconds, 1800);
            default: return defaultCooldownSeconds;
        }
    }

    public void refreshCache() {
        loadConfigs();
    }
}
