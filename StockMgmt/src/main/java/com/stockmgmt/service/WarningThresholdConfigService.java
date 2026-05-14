package com.stockmgmt.service;

import com.stockmgmt.entity.WarningThresholdConfig;
import com.stockmgmt.entity.Stock;
import com.stockmgmt.repository.WarningThresholdConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WarningThresholdConfigService {

    private static final Logger logger = LoggerFactory.getLogger(WarningThresholdConfigService.class);

    @Autowired
    private WarningThresholdConfigRepository configRepository;

    @Value("${stock.warning.default-low-stock-threshold:10}")
    private int defaultLowStockThreshold;

    @Value("${stock.warning.default-overstock-threshold:500}")
    private int defaultOverstockThreshold;

    @Value("${stock.warning.default-low-stock-turnover-days:7}")
    private int defaultLowStockTurnoverDays;

    @Value("${stock.warning.default-overstock-turnover-days:90}")
    private int defaultOverstockTurnoverDays;

    private final Map<String, WarningThresholdConfig> configCache = new ConcurrentHashMap<>();

    public static final String CONFIG_TYPE_GLOBAL = "GLOBAL";
    public static final String CONFIG_TYPE_WAREHOUSE = "WAREHOUSE";
    public static final String CONFIG_TYPE_PRODUCT = "PRODUCT";
    public static final String CONFIG_TYPE_SKU = "SKU";

    @PostConstruct
    public void init() {
        loadConfigs();
    }

    public void loadConfigs() {
        logger.info("加载预警阈值配置...");
        configCache.clear();
        configRepository.findByEnabledTrue().forEach(config -> {
            String key = buildCacheKey(config.getConfigType(), config.getSkuId(),
                    config.getProductId(), config.getWarehouseId());
            configCache.put(key, config);
            logger.debug("加载配置: type={}, sku={}, product={}, warehouse={}, low={}, over={}",
                    config.getConfigType(), config.getSkuId(), config.getProductId(),
                    config.getWarehouseId(), config.getLowStockThreshold(),
                    config.getOverstockThreshold());
        });
        logger.info("预警阈值配置加载完成，共加载 {} 条配置", configCache.size());
    }

    public WarningThresholdConfig getConfig(Stock stock) {
        return getConfig(stock.getSkuId(), stock.getProductId(), stock.getWarehouseId());
    }

    public WarningThresholdConfig getConfig(String skuId, String productId, String warehouseId) {
        logger.debug("获取预警阈值配置: sku={}, product={}, warehouse={}",
                skuId, productId, warehouseId);

        String skuKey = buildCacheKey(CONFIG_TYPE_SKU, skuId, productId, warehouseId);
        WarningThresholdConfig config = configCache.get(skuKey);
        if (config != null) {
            logger.debug("命中SKU级配置: low={}, over={}",
                    config.getLowStockThreshold(), config.getOverstockThreshold());
            return config;
        }

        String productKey = buildCacheKey(CONFIG_TYPE_PRODUCT, null, productId, warehouseId);
        config = configCache.get(productKey);
        if (config != null) {
            logger.debug("命中商品级配置: low={}, over={}",
                    config.getLowStockThreshold(), config.getOverstockThreshold());
            return config;
        }

        String warehouseKey = buildCacheKey(CONFIG_TYPE_WAREHOUSE, null, null, warehouseId);
        config = configCache.get(warehouseKey);
        if (config != null) {
            logger.debug("命中仓库级配置: low={}, over={}",
                    config.getLowStockThreshold(), config.getOverstockThreshold());
            return config;
        }

        String globalKey = buildCacheKey(CONFIG_TYPE_GLOBAL, null, null, null);
        config = configCache.get(globalKey);
        if (config != null) {
            logger.debug("命中全局配置: low={}, over={}",
                    config.getLowStockThreshold(), config.getOverstockThreshold());
            return config;
        }

        logger.debug("使用默认配置: low={}, over={}",
                defaultLowStockThreshold, defaultOverstockThreshold);
        return buildDefaultConfig();
    }

    public int getLowStockThreshold(Stock stock) {
        WarningThresholdConfig config = getConfig(stock);
        return config.getLowStockThreshold() != null ?
                config.getLowStockThreshold() : defaultLowStockThreshold;
    }

    public int getOverstockThreshold(Stock stock) {
        WarningThresholdConfig config = getConfig(stock);
        return config.getOverstockThreshold() != null ?
                config.getOverstockThreshold() : defaultOverstockThreshold;
    }

    public int getLowStockTurnoverDays(Stock stock) {
        WarningThresholdConfig config = getConfig(stock);
        return config.getLowStockTurnoverDays() != null ?
                config.getLowStockTurnoverDays() : defaultLowStockTurnoverDays;
    }

    public int getOverstockTurnoverDays(Stock stock) {
        WarningThresholdConfig config = getConfig(stock);
        return config.getOverstockTurnoverDays() != null ?
                config.getOverstockTurnoverDays() : defaultOverstockTurnoverDays;
    }

    public boolean shouldTriggerLowStockWarning(Stock stock) {
        int threshold = getLowStockThreshold(stock);
        return stock.getCurrentQuantity() <= threshold;
    }

    public boolean shouldTriggerOverstockWarning(Stock stock) {
        int threshold = getOverstockThreshold(stock);
        return stock.getCurrentQuantity() >= threshold;
    }

    public WarningThresholdConfig saveConfig(WarningThresholdConfig config) {
        WarningThresholdConfig saved = configRepository.save(config);
        loadConfigs();
        logger.info("预警阈值配置已保存: id={}, type={}, low={}, over={}",
                saved.getId(), saved.getConfigType(),
                saved.getLowStockThreshold(), saved.getOverstockThreshold());
        return saved;
    }

    public void deleteConfig(Long id) {
        configRepository.deleteById(id);
        loadConfigs();
        logger.info("预警阈值配置已删除: id={}", id);
    }

    public Optional<WarningThresholdConfig> getConfigById(Long id) {
        return configRepository.findById(id);
    }

    public List<WarningThresholdConfig> getAllConfigs() {
        return configRepository.findAll();
    }

    public List<WarningThresholdConfig> getConfigsByType(String configType) {
        return configRepository.findByConfigType(configType);
    }

    private String buildCacheKey(String configType, String skuId, String productId, String warehouseId) {
        return String.format("%s|%s|%s|%s",
                configType != null ? configType : "NULL",
                skuId != null ? skuId : "NULL",
                productId != null ? productId : "NULL",
                warehouseId != null ? warehouseId : "NULL");
    }

    private WarningThresholdConfig buildDefaultConfig() {
        return WarningThresholdConfig.builder()
                .configType(CONFIG_TYPE_GLOBAL)
                .lowStockThreshold(defaultLowStockThreshold)
                .overstockThreshold(defaultOverstockThreshold)
                .lowStockTurnoverDays(defaultLowStockTurnoverDays)
                .overstockTurnoverDays(defaultOverstockTurnoverDays)
                .priority(0)
                .enabled(true)
                .build();
    }

    public void applyThresholds(Stock stock) {
        WarningThresholdConfig config = getConfig(stock);
        if (config.getLowStockThreshold() != null) {
            stock.setWarningThreshold(config.getLowStockThreshold());
        }
        if (config.getOverstockThreshold() != null) {
            stock.setOverstockThreshold(config.getOverstockThreshold());
        }
    }

    public void refreshCache() {
        loadConfigs();
    }
}
