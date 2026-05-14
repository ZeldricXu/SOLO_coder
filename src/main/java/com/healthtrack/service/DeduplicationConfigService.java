package com.healthtrack.service;

import com.healthtrack.entity.DeduplicationConfig;
import com.healthtrack.repository.DeduplicationConfigRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DeduplicationConfigService {

    private static final Logger logger = LoggerFactory.getLogger(DeduplicationConfigService.class);

    @Autowired
    private DeduplicationConfigRepository deduplicationConfigRepository;

    @Value("${healthtrack.deduplication.window.high-priority-minutes:30}")
    private int defaultHighPriorityMinutes;

    @Value("${healthtrack.deduplication.window.medium-priority-minutes:120}")
    private int defaultMediumPriorityMinutes;

    @Value("${healthtrack.deduplication.window.low-priority-minutes:240}")
    private int defaultLowPriorityMinutes;

    @Value("${healthtrack.deduplication.window.default-minutes:60}")
    private int defaultWindowMinutes;

    private final Map<String, Integer> windowCache = new ConcurrentHashMap<>();
    private final Map<String, DeduplicationConfig> configCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        logger.info("初始化去重窗口配置服务");
        initializeDefaultConfigs();
        loadConfigsToCache();
    }

    private void initializeDefaultConfigs() {
        try {
            long existingCount = deduplicationConfigRepository.count();
            if (existingCount == 0) {
                logger.info("创建默认去重窗口配置");
                
                DeduplicationConfig highConfig = new DeduplicationConfig(
                        "high",
                        defaultHighPriorityMinutes,
                        "高优先级建议去重窗口（紧急建议快速推送）"
                );
                
                DeduplicationConfig mediumConfig = new DeduplicationConfig(
                        "medium",
                        defaultMediumPriorityMinutes,
                        "中优先级建议去重窗口（普通建议聚合推送）"
                );
                
                DeduplicationConfig lowConfig = new DeduplicationConfig(
                        "low",
                        defaultLowPriorityMinutes,
                        "低优先级建议去重窗口（维护建议延迟推送）"
                );
                
                deduplicationConfigRepository.saveAll(List.of(highConfig, mediumConfig, lowConfig));
                logger.info("默认去重窗口配置创建完成");
            }
        } catch (Exception e) {
            logger.warn("初始化默认去重窗口配置失败，将使用配置文件默认值: {}", e.getMessage());
        }
    }

    private void loadConfigsToCache() {
        try {
            List<DeduplicationConfig> configs = deduplicationConfigRepository.findByEnabledTrue();
            for (DeduplicationConfig config : configs) {
                windowCache.put(config.getPriority().toLowerCase(), config.getWindowMinutes());
                configCache.put(config.getPriority().toLowerCase(), config);
            }
            logger.info("已加载 {} 个去重窗口配置到缓存", configs.size());
        } catch (Exception e) {
            logger.error("加载去重窗口配置到缓存失败: {}", e.getMessage(), e);
        }
    }

    public int getWindowMinutes(String priority) {
        if (priority == null) {
            return defaultWindowMinutes;
        }
        
        String lowerPriority = priority.toLowerCase();
        
        Integer cachedMinutes = windowCache.get(lowerPriority);
        if (cachedMinutes != null) {
            return cachedMinutes;
        }
        
        Optional<DeduplicationConfig> configOpt = deduplicationConfigRepository.findByPriorityAndEnabledTrue(lowerPriority);
        if (configOpt.isPresent()) {
            DeduplicationConfig config = configOpt.get();
            windowCache.put(lowerPriority, config.getWindowMinutes());
            configCache.put(lowerPriority, config);
            return config.getWindowMinutes();
        }
        
        return getDefaultWindowByPriority(priority);
    }

    public long getWindowMillis(String priority) {
        return (long) getWindowMinutes(priority) * 60 * 1000;
    }

    private int getDefaultWindowByPriority(String priority) {
        switch (priority.toLowerCase()) {
            case "high":
                return defaultHighPriorityMinutes;
            case "medium":
                return defaultMediumPriorityMinutes;
            case "low":
                return defaultLowPriorityMinutes;
            default:
                return defaultWindowMinutes;
        }
    }

    public DeduplicationConfig getConfig(String priority) {
        if (priority == null) {
            return null;
        }
        
        String lowerPriority = priority.toLowerCase();
        
        DeduplicationConfig cached = configCache.get(lowerPriority);
        if (cached != null) {
            return cached;
        }
        
        Optional<DeduplicationConfig> configOpt = deduplicationConfigRepository.findByPriority(lowerPriority);
        if (configOpt.isPresent()) {
            DeduplicationConfig config = configOpt.get();
            configCache.put(lowerPriority, config);
            windowCache.put(lowerPriority, config.getWindowMinutes());
            return config;
        }
        
        return null;
    }

    public List<DeduplicationConfig> getAllConfigs() {
        return deduplicationConfigRepository.findAll();
    }

    public List<DeduplicationConfig> getEnabledConfigs() {
        return deduplicationConfigRepository.findByEnabledTrue();
    }

    public DeduplicationConfig updateConfig(String priority, int windowMinutes, String description) {
        Optional<DeduplicationConfig> configOpt = deduplicationConfigRepository.findByPriority(priority.toLowerCase());
        
        DeduplicationConfig config;
        if (configOpt.isPresent()) {
            config = configOpt.get();
            config.setWindowMinutes(windowMinutes);
            if (description != null) {
                config.setDescription(description);
            }
            config.setUpdatedAt(LocalDateTime.now());
        } else {
            config = new DeduplicationConfig(priority.toLowerCase(), windowMinutes, description);
        }
        
        DeduplicationConfig saved = deduplicationConfigRepository.save(config);
        invalidateCache(priority);
        logger.info("更新去重窗口配置: priority={}, windowMinutes={}", priority, windowMinutes);
        return saved;
    }

    public boolean enableConfig(String priority) {
        Optional<DeduplicationConfig> configOpt = deduplicationConfigRepository.findByPriority(priority.toLowerCase());
        if (configOpt.isPresent()) {
            DeduplicationConfig config = configOpt.get();
            config.setEnabled(true);
            config.setUpdatedAt(LocalDateTime.now());
            deduplicationConfigRepository.save(config);
            invalidateCache(priority);
            logger.info("启用去重窗口配置: priority={}", priority);
            return true;
        }
        return false;
    }

    public boolean disableConfig(String priority) {
        Optional<DeduplicationConfig> configOpt = deduplicationConfigRepository.findByPriority(priority.toLowerCase());
        if (configOpt.isPresent()) {
            DeduplicationConfig config = configOpt.get();
            config.setEnabled(false);
            config.setUpdatedAt(LocalDateTime.now());
            deduplicationConfigRepository.save(config);
            invalidateCache(priority);
            logger.info("禁用去重窗口配置: priority={}", priority);
            return true;
        }
        return false;
    }

    public void refreshCache() {
        windowCache.clear();
        configCache.clear();
        loadConfigsToCache();
        logger.info("去重窗口配置缓存已刷新");
    }

    private void invalidateCache(String priority) {
        if (priority != null) {
            String lowerPriority = priority.toLowerCase();
            windowCache.remove(lowerPriority);
            configCache.remove(lowerPriority);
        }
    }

    public int getDefaultHighPriorityMinutes() { return defaultHighPriorityMinutes; }
    public int getDefaultMediumPriorityMinutes() { return defaultMediumPriorityMinutes; }
    public int getDefaultLowPriorityMinutes() { return defaultLowPriorityMinutes; }
    public int getDefaultWindowMinutes() { return defaultWindowMinutes; }
}
