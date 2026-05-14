package com.stockmgmt.service;

import com.stockmgmt.entity.LockTimeoutConfig;
import com.stockmgmt.repository.LockTimeoutConfigRepository;
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
public class LockTimeoutConfigService {

    private static final Logger logger = LoggerFactory.getLogger(LockTimeoutConfigService.class);

    @Autowired
    private LockTimeoutConfigRepository configRepository;

    @Value("${stock.lock.default-timeout-seconds:30}")
    private int defaultTimeoutSeconds;

    @Value("${stock.lock.default-max-retry:3}")
    private int defaultMaxRetry;

    @Value("${stock.lock.default-retry-delay:1}")
    private int defaultRetryDelaySeconds;

    private final Map<String, LockTimeoutConfig> configCache = new ConcurrentHashMap<>();

    public static final String URGENCY_URGENT = "URGENT";
    public static final String URGENCY_NORMAL = "NORMAL";
    public static final String URGENCY_LOW = "LOW";

    @PostConstruct
    public void init() {
        loadConfigs();
    }

    public void loadConfigs() {
        logger.info("加载锁定超时配置...");
        configCache.clear();
        configRepository.findByEnabledTrue().forEach(config -> {
            String key = buildCacheKey(config.getProductId(), config.getWarehouseId(), config.getUrgencyLevel());
            configCache.put(key, config);
            logger.debug("加载配置: productId={}, warehouseId={}, urgency={}, timeout={}s",
                    config.getProductId(), config.getWarehouseId(), config.getUrgencyLevel(), config.getTimeoutSeconds());
        });
        logger.info("锁定超时配置加载完成，共加载 {} 条配置", configCache.size());
    }

    public LockTimeoutConfig getConfig(String productId, String warehouseId, String urgencyLevel) {
        logger.debug("获取锁定超时配置: productId={}, warehouseId={}, urgency={}",
                productId, warehouseId, urgencyLevel);

        String specificKey = buildCacheKey(productId, warehouseId, urgencyLevel);
        LockTimeoutConfig config = configCache.get(specificKey);
        if (config != null) {
            logger.debug("命中具体商品+仓库配置: timeout={}s", config.getTimeoutSeconds());
            return config;
        }

        String productKey = buildCacheKey(productId, null, urgencyLevel);
        config = configCache.get(productKey);
        if (config != null) {
            logger.debug("命中商品级配置: timeout={}s", config.getTimeoutSeconds());
            return config;
        }

        String warehouseKey = buildCacheKey(null, warehouseId, urgencyLevel);
        config = configCache.get(warehouseKey);
        if (config != null) {
            logger.debug("命中仓库级配置: timeout={}s", config.getTimeoutSeconds());
            return config;
        }

        String urgencyKey = buildCacheKey(null, null, urgencyLevel);
        config = configCache.get(urgencyKey);
        if (config != null) {
            logger.debug("命中紧急程度默认配置: timeout={}s", config.getTimeoutSeconds());
            return config;
        }

        logger.debug("使用默认配置: timeout={}s", defaultTimeoutSeconds);
        return buildDefaultConfig(urgencyLevel);
    }

    public int getTimeoutSeconds(String productId, String warehouseId, String urgencyLevel) {
        LockTimeoutConfig config = getConfig(productId, warehouseId, urgencyLevel);
        return config.getTimeoutSeconds();
    }

    public int getMaxRetryTimes(String productId, String warehouseId, String urgencyLevel) {
        LockTimeoutConfig config = getConfig(productId, warehouseId, urgencyLevel);
        return config.getMaxRetryTimes();
    }

    public int getRetryDelaySeconds(String productId, String warehouseId, String urgencyLevel) {
        LockTimeoutConfig config = getConfig(productId, warehouseId, urgencyLevel);
        return config.getRetryDelaySeconds();
    }

    public boolean isUrgent(String urgencyLevel) {
        return URGENCY_URGENT.equalsIgnoreCase(urgencyLevel);
    }

    public LockTimeoutConfig saveConfig(LockTimeoutConfig config) {
        LockTimeoutConfig saved = configRepository.save(config);
        loadConfigs();
        logger.info("锁定超时配置已保存: id={}, urgency={}, timeout={}s",
                saved.getId(), saved.getUrgencyLevel(), saved.getTimeoutSeconds());
        return saved;
    }

    public void deleteConfig(Long id) {
        configRepository.deleteById(id);
        loadConfigs();
        logger.info("锁定超时配置已删除: id={}", id);
    }

    public Optional<LockTimeoutConfig> getConfigById(Long id) {
        return configRepository.findById(id);
    }

    private String buildCacheKey(String productId, String warehouseId, String urgencyLevel) {
        return String.format("%s|%s|%s",
                productId != null ? productId : "NULL",
                warehouseId != null ? warehouseId : "NULL",
                urgencyLevel != null ? urgencyLevel : "NULL");
    }

    private LockTimeoutConfig buildDefaultConfig(String urgencyLevel) {
        int timeout = defaultTimeoutSeconds;
        int maxRetry = defaultMaxRetry;
        int retryDelay = defaultRetryDelaySeconds;

        if (URGENCY_URGENT.equalsIgnoreCase(urgencyLevel)) {
            timeout = Math.min(timeout, 5);
            maxRetry = 1;
            retryDelay = 0;
        } else if (URGENCY_LOW.equalsIgnoreCase(urgencyLevel)) {
            timeout = Math.max(timeout, 60);
            maxRetry = 5;
            retryDelay = 3;
        }

        return LockTimeoutConfig.builder()
                .urgencyLevel(urgencyLevel)
                .timeoutSeconds(timeout)
                .maxRetryTimes(maxRetry)
                .retryDelaySeconds(retryDelay)
                .enabled(true)
                .build();
    }

    public void refreshCache() {
        loadConfigs();
    }
}
