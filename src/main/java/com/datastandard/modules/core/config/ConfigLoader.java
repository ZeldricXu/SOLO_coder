package com.datastandard.modules.core.config;

import com.datastandard.modules.core.dto.StandardizationConfig;
import com.datastandard.modules.core.dto.TransformRequest;
import com.datastandard.modules.core.entity.StandardizationTemplate;
import com.datastandard.modules.core.mapper.StandardizationTemplateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
public class ConfigLoader {

    private final StandardizationTemplateMapper templateMapper;
    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;
    private final Counter configLoadCounter;

    private static final String CACHE_NAME = "standardizationConfigs";

    public ConfigLoader(StandardizationTemplateMapper templateMapper,
                        ObjectMapper objectMapper,
                        CacheManager cacheManager,
                        MeterRegistry meterRegistry) {
        this.templateMapper = templateMapper;
        this.objectMapper = objectMapper;
        this.cacheManager = cacheManager;
        this.configLoadCounter = Counter.builder("core.config.load")
                .description("配置加载次数")
                .register(meterRegistry);
    }

    public StandardizationConfig loadConfiguration(TransformRequest request) {
        if (request.getConfig() != null) {
            return request.getConfig();
        }

        String cacheKey = buildCacheKey(request);
        StandardizationConfig cached = loadFromCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        return loadFromDatabase(request, cacheKey);
    }

    private StandardizationConfig loadFromCache(String cacheKey) {
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache != null) {
            StandardizationConfig cached = cache.get(cacheKey, StandardizationConfig.class);
            if (cached != null) {
                log.debug("从缓存加载配置: {}", cacheKey);
                configLoadCounter.increment();
                return cached;
            }
        }
        return null;
    }

    private StandardizationConfig loadFromDatabase(TransformRequest request, String cacheKey) {
        log.debug("从数据库加载配置: dataSource={}, dataset={}", request.getDataSource(), request.getDatasetName());

        Optional<StandardizationTemplate> templateOpt = templateMapper
                .findActiveByDataSourceAndDataset(request.getDataSource(), request.getDatasetName());

        if (templateOpt.isPresent()) {
            StandardizationConfig config = parseTemplate(templateOpt.get());
            cacheConfig(cacheKey, config);
            return config;
        }

        log.warn("未找到配置，使用默认配置: dataSource={}, dataset={}", request.getDataSource(), request.getDatasetName());
        return createDefaultConfig();
    }

    private StandardizationConfig parseTemplate(StandardizationTemplate template) {
        try {
            return objectMapper.readValue(template.getConfig(), StandardizationConfig.class);
        } catch (Exception e) {
            log.error("解析配置模板失败: templateId={}", template.getTemplateId(), e);
            return createDefaultConfig();
        }
    }

    private void cacheConfig(String cacheKey, StandardizationConfig config) {
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache != null) {
            cache.put(cacheKey, config);
        }
        configLoadCounter.increment();
    }

    private String buildCacheKey(TransformRequest request) {
        return "config:" + request.getDataSource() + ":" + request.getDatasetName();
    }

    private StandardizationConfig createDefaultConfig() {
        return StandardizationConfig.builder()
                .configId("default")
                .configVersion("1.0")
                .fieldRules(new java.util.ArrayList<>())
                .enableDataCleaning(true)
                .enableTypeConversion(true)
                .enableValidation(true)
                .failOnError(false)
                .maxParallelism(4)
                .timeoutMs(30000)
                .build();
    }
}
