package com.delivery.tracker.privacy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 隐私配置管理器
 * 支持动态配置和运行时热更新
 */
@Slf4j
@Component
public class PrivacyConfigManager {

    private final Map<String, PrivacyConfig> configStore = new ConcurrentHashMap<>();
    private final Cache<String, PrivacyConfig> configCache;
    private final AtomicLong versionCounter = new AtomicLong(0);

    public PrivacyConfigManager() {
        this.configCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .recordStats()
                .build();
    }

    @PostConstruct
    public void init() {
        loadDefaultConfigs();
        log.info("隐私配置管理器初始化完成，默认配置数量: {}", configStore.size());
    }

    private void loadDefaultConfigs() {
        PrivacyConfig defaultConfig = PrivacyConfig.builder()
                .configId("CFG_DEFAULT")
                .scene("DEFAULT")
                .strategyName("LAPLACE")
                .sensitivity(1.0)
                .epsilon(0.1)
                .delta(0.00001)
                .noiseDistribution("LAPLACE")
                .scaleFactor(1.0)
                .enabled(true)
                .priority(0)
                .version(versionCounter.incrementAndGet())
                .description("默认隐私配置，适用于一般场景")
                .build();
        addConfig(defaultConfig);

        PrivacyConfig highPrivacyConfig = PrivacyConfig.builder()
                .configId("CFG_HIGH_PRIVACY")
                .scene("HIGH_PRIVACY")
                .strategyName("LAPLACE")
                .sensitivity(1.0)
                .epsilon(0.01)
                .delta(0.000001)
                .noiseDistribution("LAPLACE")
                .scaleFactor(2.0)
                .enabled(true)
                .priority(100)
                .version(versionCounter.incrementAndGet())
                .description("高隐私保护配置，适用于敏感数据场景")
                .build();
        addConfig(highPrivacyConfig);

        PrivacyConfig statsConfig = PrivacyConfig.builder()
                .configId("CFG_STATISTICAL")
                .scene("STATISTICAL_QUERY")
                .strategyName("GAUSSIAN")
                .sensitivity(1.0)
                .epsilon(0.5)
                .delta(0.0001)
                .noiseDistribution("GAUSSIAN")
                .scaleFactor(1.0)
                .enabled(true)
                .priority(50)
                .version(versionCounter.incrementAndGet())
                .description("统计查询配置，使用Gaussian机制")
                .build();
        addConfig(statsConfig);

        PrivacyConfig lowSensitivityConfig = PrivacyConfig.builder()
                .configId("CFG_LOW_SENSITIVITY")
                .scene("LOW_SENSITIVITY")
                .strategyName("NO_OP")
                .sensitivity(0.1)
                .epsilon(1.0)
                .delta(0.001)
                .noiseDistribution("NONE")
                .scaleFactor(0.0)
                .enabled(true)
                .priority(10)
                .version(versionCounter.incrementAndGet())
                .description("低敏感度配置，不添加噪声")
                .build();
        addConfig(lowSensitivityConfig);

        PrivacyConfig categoricalConfig = PrivacyConfig.builder()
                .configId("CFG_CATEGORICAL")
                .scene("CATEGORICAL_QUERY")
                .strategyName("EXPONENTIAL")
                .sensitivity(1.0)
                .epsilon(0.3)
                .delta(0.0001)
                .noiseDistribution("EXPONENTIAL")
                .scaleFactor(1.0)
                .enabled(true)
                .priority(30)
                .version(versionCounter.incrementAndGet())
                .description("分类查询配置，使用Exponential机制")
                .build();
        addConfig(categoricalConfig);
    }

    /**
     * 添加或更新配置
     */
    public void addConfig(PrivacyConfig config) {
        config.setVersion(versionCounter.incrementAndGet());
        configStore.put(config.getConfigId(), config);
        configCache.put(config.getConfigId(), config);
        log.info("隐私配置已更新: {}, 版本: {}", config.getConfigId(), config.getVersion());
    }

    /**
     * 获取配置
     */
    public PrivacyConfig getConfig(String configId) {
        if (configId == null) {
            return getDefaultConfig();
        }

        PrivacyConfig cached = configCache.getIfPresent(configId);
        if (cached != null) {
            return cached;
        }

        PrivacyConfig config = configStore.get(configId);
        if (config != null) {
            configCache.put(configId, config);
        }
        return config != null ? config : getDefaultConfig();
    }

    /**
     * 根据场景获取最优配置
     */
    public PrivacyConfig getConfigForScene(String scene) {
        return configStore.values().stream()
                .filter(PrivacyConfig::isEnabled)
                .filter(c -> c.getScene().equals(scene) || "DEFAULT".equals(c.getScene()))
                .max((c1, c2) -> Integer.compare(c1.getPriority(), c2.getPriority()))
                .orElse(getDefaultConfig());
    }

    /**
     * 获取默认配置
     */
    public PrivacyConfig getDefaultConfig() {
        return configStore.values().stream()
                .filter(PrivacyConfig::isEnabled)
                .filter(c -> "DEFAULT".equals(c.getScene()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("默认配置不存在"));
    }

    /**
     * 热更新配置
     */
    public void updateConfig(String configId, PrivacyConfig newConfig) {
        PrivacyConfig existing = configStore.get(configId);
        if (existing == null) {
            throw new IllegalArgumentException("配置不存在: " + configId);
        }

        newConfig.setConfigId(configId);
        newConfig.setVersion(versionCounter.incrementAndGet());
        configStore.put(configId, newConfig);
        configCache.invalidate(configId);

        log.info("隐私配置热更新完成: {}, 新版本: {}", configId, newConfig.getVersion());
    }

    /**
     * 启用/禁用配置
     */
    public void setConfigEnabled(String configId, boolean enabled) {
        PrivacyConfig config = configStore.get(configId);
        if (config != null) {
            config.setEnabled(enabled);
            config.setVersion(versionCounter.incrementAndGet());
            configCache.invalidate(configId);
            log.info("隐私配置状态更新: {}, enabled={}", configId, enabled);
        }
    }

    /**
     * 删除配置
     */
    public void removeConfig(String configId) {
        if ("CFG_DEFAULT".equals(configId)) {
            throw new IllegalArgumentException("不能删除默认配置");
        }
        configStore.remove(configId);
        configCache.invalidate(configId);
        log.info("隐私配置已删除: {}", configId);
    }

    /**
     * 获取所有配置
     */
    public Map<String, PrivacyConfig> getAllConfigs() {
        return Map.copyOf(configStore);
    }

    /**
     * 获取配置版本
     */
    public long getConfigVersion(String configId) {
        PrivacyConfig config = configStore.get(configId);
        return config != null ? config.getVersion() : -1;
    }

    /**
     * 重新加载所有配置（模拟热更新）
     */
    public void reloadConfigs() {
        configCache.invalidateAll();
        versionCounter.incrementAndGet();
        log.info("隐私配置缓存已全部刷新");
    }
}
