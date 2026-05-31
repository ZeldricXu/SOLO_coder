package com.iotplatform.config.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iotplatform.common.constant.CacheConstants;
import com.iotplatform.common.constant.ErrorCodeConstants;
import com.iotplatform.common.constant.MetricConstants;
import com.iotplatform.common.exception.BusinessException;
import com.iotplatform.common.util.CacheKeyUtil;
import com.iotplatform.config.dto.ConfigCreateDTO;
import com.iotplatform.config.dto.ConfigRollbackDTO;
import com.iotplatform.config.dto.ConfigUpdateDTO;
import com.iotplatform.config.entity.SysConfig;
import com.iotplatform.config.entity.SysConfigHistory;
import com.iotplatform.config.mapper.SysConfigHistoryMapper;
import com.iotplatform.config.mapper.SysConfigMapper;
import com.iotplatform.config.service.ConfigService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigServiceImpl implements ConfigService {

    private final SysConfigMapper configMapper;
    private final SysConfigHistoryMapper historyMapper;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    private final Cache<String, SysConfig> configCache = Caffeine.newBuilder()
            .maximumSize(CacheConstants.CONFIG_CACHE_MAX_SIZE)
            .expireAfterWrite(Duration.ofSeconds(CacheConstants.CONFIG_CACHE_SECONDS))
            .recordStats()
            .build();

    private static final Duration REDIS_CACHE_DURATION = Duration.ofSeconds(CacheConstants.CONFIG_CACHE_SECONDS);

    @Override
    @Transactional
    public Mono<SysConfig> createConfig(ConfigCreateDTO dto) {
        return timedOperation(MetricConstants.CONFIG_CREATE_LATENCY, () ->
                Mono.fromCallable(() -> {
                    Optional<SysConfig> existing = configMapper.findLatest(dto.getConfigId(), dto.getNamespace());
                    int newVersion = existing.map(c -> c.getVersion() + 1).orElse(1);

                    existing.ifPresent(config -> saveToHistory(config, null));

                    SysConfig config = buildConfig(dto, newVersion);
                    configMapper.insert(config);

                    updateCaches(dto.getNamespace(), dto.getConfigKey(), config);

                    log.info("Config created: {}@{} version {}", dto.getConfigId(), dto.getNamespace(), newVersion);
                    meterRegistry.counter(MetricConstants.CONFIG_CREATE_SUCCESS).increment();
                    return config;
                })
                .doOnError(e -> {
                    log.error("Failed to create config: {}", e.getMessage(), e);
                    meterRegistry.counter(MetricConstants.CONFIG_CREATE_FAILURE).increment();
                })
                .onErrorMap(e -> new BusinessException(ErrorCodeConstants.INTERNAL_ERROR, "创建配置失败: " + e.getMessage()))
        );
    }

    @Override
    @Transactional
    public Mono<SysConfig> updateConfig(ConfigUpdateDTO dto) {
        return timedOperation(MetricConstants.CONFIG_UPDATE_LATENCY, () ->
                Mono.fromCallable(() -> {
                    SysConfig current = configMapper.findLatest(dto.getConfigId(), dto.getNamespace())
                            .orElseThrow(() -> new BusinessException(ErrorCodeConstants.CONFIG_NOT_FOUND, "配置不存在"));

                    saveToHistory(current, null);

                    SysConfig newConfig = buildUpdatedConfig(current, dto);
                    configMapper.insert(newConfig);

                    updateCaches(dto.getNamespace(), current.getConfigKey(), newConfig);

                    log.info("Config updated: {}@{} version {}", dto.getConfigId(), dto.getNamespace(), newConfig.getVersion());
                    meterRegistry.counter(MetricConstants.CONFIG_UPDATE_SUCCESS).increment();
                    return newConfig;
                })
                .doOnError(e -> {
                    log.error("Failed to update config: {}", e.getMessage(), e);
                    meterRegistry.counter(MetricConstants.CONFIG_UPDATE_FAILURE).increment();
                })
        );
    }

    @Override
    public Mono<SysConfig> getConfig(String configId, String namespace) {
        return Mono.fromCallable(() -> configMapper.findLatest(configId, namespace)
                .orElseThrow(() -> new BusinessException(ErrorCodeConstants.CONFIG_NOT_FOUND, "配置不存在")));
    }

    @Override
    public Mono<SysConfig> getConfigByVersion(String configId, String namespace, Integer version) {
        return Mono.fromCallable(() -> configMapper.findByVersion(configId, namespace, version)
                .orElseThrow(() -> new BusinessException(ErrorCodeConstants.CONFIG_VERSION_NOT_FOUND, "指定版本的配置不存在")));
    }

    @Override
    public Mono<Optional<SysConfig>> getConfigByKey(String namespace, String configKey) {
        String cacheKey = CacheKeyUtil.configKey(namespace, configKey);
        SysConfig cached = configCache.getIfPresent(cacheKey);

        if (cached != null) {
            meterRegistry.counter(MetricConstants.CONFIG_CACHE_HIT).increment();
            return Mono.just(Optional.of(cached));
        }

        meterRegistry.counter(MetricConstants.CONFIG_CACHE_MISS).increment();
        return Mono.fromCallable(() -> {
            Optional<SysConfig> configOpt = configMapper.findByNamespaceAndKey(namespace, configKey);
            configOpt.ifPresent(config -> updateCaches(namespace, configKey, config));
            return configOpt;
        });
    }

    @Override
    public Mono<IPage<SysConfig>> listConfigs(String namespace, String configKey, Boolean enabled,
                                              Integer pageNum, Integer pageSize) {
        return Mono.fromCallable(() -> {
            Page<SysConfig> page = new Page<>(pageNum, pageSize);
            return configMapper.selectConfigPage(page, namespace, configKey, enabled);
        });
    }

    @Override
    public Mono<List<SysConfigHistory>> getConfigHistory(String configId, String namespace) {
        return Mono.fromCallable(() -> historyMapper.findByConfigId(configId, namespace));
    }

    @Override
    @Transactional
    public Mono<SysConfig> rollbackConfig(ConfigRollbackDTO dto) {
        return timedOperation(MetricConstants.CONFIG_ROLLBACK_LATENCY, () ->
                Mono.fromCallable(() -> {
                    SysConfigHistory targetHistory = historyMapper.findByConfigIdAndVersion(
                            dto.getConfigId(), dto.getNamespace(), dto.getTargetVersion());
                    if (targetHistory == null) {
                        throw new BusinessException(ErrorCodeConstants.CONFIG_VERSION_NOT_FOUND, "目标版本不存在");
                    }

                    SysConfig current = configMapper.findLatest(dto.getConfigId(), dto.getNamespace())
                            .orElseThrow(() -> new BusinessException(ErrorCodeConstants.CONFIG_NOT_FOUND, "配置不存在"));

                    saveToHistory(current, dto.getTargetVersion());

                    SysConfig rolledBack = buildRolledBackConfig(targetHistory, current, dto);
                    configMapper.insert(rolledBack);

                    invalidateCaches(dto.getNamespace(), targetHistory.getConfigKey());

                    log.info("Config rolled back: {}@{} to version {}", dto.getConfigId(), dto.getNamespace(), dto.getTargetVersion());
                    meterRegistry.counter(MetricConstants.CONFIG_ROLLBACK_SUCCESS).increment();
                    return rolledBack;
                })
                .doOnError(e -> {
                    log.error("Failed to rollback config: {}", e.getMessage(), e);
                    meterRegistry.counter(MetricConstants.CONFIG_ROLLBACK_FAILURE).increment();
                })
        );
    }

    @Override
    @Transactional
    public Mono<Void> deleteConfig(String configId, String namespace) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<SysConfig> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SysConfig::getConfigId, configId)
                    .eq(SysConfig::getNamespace, namespace);
            configMapper.delete(wrapper);

            log.info("Config deleted: {}@{}", configId, namespace);
            return null;
        });
    }

    @Override
    public Mono<Boolean> validateConfig(String configKey, String configValue) {
        return Mono.just(configValue != null && !configValue.trim().isEmpty());
    }

    private SysConfig buildConfig(ConfigCreateDTO dto, int version) {
        SysConfig config = new SysConfig();
        config.setConfigId(dto.getConfigId());
        config.setNamespace(dto.getNamespace());
        config.setConfigKey(dto.getConfigKey());
        config.setConfigValue(dto.getConfigValue());
        config.setDescription(dto.getDescription());
        config.setEnabled(dto.getEnabled());
        config.setVersion(version);
        config.setCreatedBy(dto.getCreatedBy());
        config.setAppliedAt(LocalDateTime.now());
        return config;
    }

    private SysConfig buildUpdatedConfig(SysConfig current, ConfigUpdateDTO dto) {
        SysConfig newConfig = new SysConfig();
        newConfig.setConfigId(dto.getConfigId());
        newConfig.setNamespace(dto.getNamespace());
        newConfig.setConfigKey(current.getConfigKey());
        newConfig.setConfigValue(dto.getConfigValue() != null ? dto.getConfigValue() : current.getConfigValue());
        newConfig.setDescription(dto.getDescription() != null ? dto.getDescription() : current.getDescription());
        newConfig.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : current.getEnabled());
        newConfig.setVersion(current.getVersion() + 1);
        newConfig.setCreatedBy(dto.getUpdatedBy());
        newConfig.setAppliedAt(LocalDateTime.now());
        return newConfig;
    }

    private SysConfig buildRolledBackConfig(SysConfigHistory history, SysConfig current, ConfigRollbackDTO dto) {
        SysConfig rolledBack = new SysConfig();
        rolledBack.setConfigId(dto.getConfigId());
        rolledBack.setNamespace(dto.getNamespace());
        rolledBack.setConfigKey(history.getConfigKey());
        rolledBack.setConfigValue(history.getConfigValue());
        rolledBack.setDescription(history.getDescription());
        rolledBack.setEnabled(history.getEnabled());
        rolledBack.setVersion(current.getVersion() + 1);
        rolledBack.setCreatedBy(dto.getRolledBackBy());
        rolledBack.setAppliedAt(LocalDateTime.now());
        return rolledBack;
    }

    private void saveToHistory(SysConfig config, Integer rollbackFromVersion) {
        SysConfigHistory history = new SysConfigHistory();
        history.setConfigId(config.getConfigId());
        history.setNamespace(config.getNamespace());
        history.setVersion(config.getVersion());
        history.setConfigKey(config.getConfigKey());
        history.setConfigValue(config.getConfigValue());
        history.setDescription(config.getDescription());
        history.setEnabled(config.getEnabled());
        history.setRollbackFromVersion(rollbackFromVersion);
        history.setCreatedAt(LocalDateTime.now());
        historyMapper.insert(history);
    }

    private void updateCaches(String namespace, String configKey, SysConfig config) {
        String cacheKey = CacheKeyUtil.configKey(namespace, configKey);
        configCache.put(cacheKey, config);
        redisTemplate.opsForValue()
                .set(cacheKey, config.getConfigValue(), REDIS_CACHE_DURATION)
                .subscribe(null, e -> log.warn("Failed to update redis cache: {}", e.getMessage()));
    }

    private void invalidateCaches(String namespace, String configKey) {
        String cacheKey = CacheKeyUtil.configKey(namespace, configKey);
        configCache.invalidate(cacheKey);
        redisTemplate.delete(cacheKey)
                .subscribe(null, e -> log.warn("Failed to invalidate redis cache: {}", e.getMessage()));
    }

    private <T> Mono<T> timedOperation(String metricName, Supplier<Mono<T>> operation) {
        Timer.Sample sample = Timer.start(meterRegistry);
        return operation.get()
                .doFinally(s -> sample.stop(meterRegistry.timer(metricName)));
    }
}
