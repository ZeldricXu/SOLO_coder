package com.solo.config.module.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.solo.config.common.IdGenerator;
import com.solo.config.common.exception.BusinessException;
import com.solo.config.entity.Config;
import com.solo.config.mapper.ConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    private final List<ConfigSource> configSources;
    private final ConfigSourceProperties properties;
    private final ConfigMapper configMapper;
    private final Cache<String, String> configCache;

    private List<ConfigSource> readSources;
    private List<ConfigSource> writeSources;

    @PostConstruct
    public void init() {
        readSources = configSources.stream()
                .filter(ConfigSource::canRead)
                .sorted(Comparator.comparingInt(ConfigSource::getPriority))
                .toList();

        writeSources = configSources.stream()
                .filter(ConfigSource::canWrite)
                .sorted(Comparator.comparingInt(ConfigSource::getPriority))
                .toList();

        log.info("Read config sources initialized: {}", readSources.stream().map(ConfigSource::getType).toList());
        log.info("Write config sources initialized: {}", writeSources.stream().map(ConfigSource::getType).toList());
    }

    public Mono<String> getConfig(String namespace, String key) {
        String cacheKey = namespace + ":" + key;
        String cachedValue = configCache.getIfPresent(cacheKey);
        if (cachedValue != null) {
            return Mono.just(cachedValue);
        }

        return Mono.fromCallable(() -> {
            for (ConfigSource source : readSources) {
                try {
                    String value = source.getConfig(namespace, key);
                    if (value != null) {
                        configCache.put(cacheKey, value);
                        log.debug("Read config from {} for {}:{}", source.getType(), namespace, key);
                        return value;
                    }
                } catch (Exception e) {
                    log.warn("Failed to get config from read source: {}, namespace: {}, key: {}",
                            source.getType(), namespace, key, e);
                }
            }
            return null;
        });
    }

    public Mono<Config> setConfig(String namespace, String key, String value, String sourceType) {
        return Mono.fromCallable(() -> {
            ConfigSource targetSource = writeSources.stream()
                    .filter(s -> s.getType().equals(sourceType))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("Write config source not found or not writable: " + sourceType));

            targetSource.setConfig(namespace, key, value);

            replicateToAllWriteSources(namespace, key, value, sourceType);

            Config existing = configMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Config>()
                            .eq("namespace", namespace)
                            .eq("enabled", true)
                            .orderByDesc("version")
                            .last("LIMIT 1")
            );

            int newVersion = existing != null ? existing.getVersion() + 1 : 1;
            Map<String, Object> parameters = existing != null ?
                    new java.util.HashMap<>(existing.getParameters()) : new java.util.HashMap<>();
            parameters.put(key, value);

            Config config = new Config();
            config.setConfigId(IdGenerator.generateConfigId());
            config.setNamespace(namespace);
            config.setVersion(newVersion);
            config.setParameters(parameters);
            config.setEnabled(true);
            config.setStatus(Config.ConfigStatus.DRAFT.name());
            config.setSourceType(sourceType);
            config.setAppliedAt(LocalDateTime.now());

            configMapper.insert(config);

            String cacheKey = namespace + ":" + key;
            configCache.invalidate(cacheKey);

            log.info("Config updated, namespace: {}, key: {}, version: {}, source: {}",
                    namespace, key, newVersion, sourceType);

            return config;
        });
    }

    private void replicateToAllWriteSources(String namespace, String key, String value, String excludeSource) {
        for (ConfigSource source : writeSources) {
            if (!source.getType().equals(excludeSource) && !source.isReadOnly()) {
                try {
                    source.setConfig(namespace, key, value);
                    log.debug("Replicated config to {} for {}:{}", source.getType(), namespace, key);
                } catch (Exception e) {
                    log.warn("Failed to replicate config to {}, namespace: {}, key: {}",
                            source.getType(), namespace, key, e);
                }
            }
        }
    }

    @Scheduled(fixedDelayString = "${config.multi-source.refresh-interval:30000}")
    public void refreshConfigs() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            readSources.forEach(ConfigSource::refresh);
            configCache.invalidateAll();
            log.debug("All read config sources refreshed and cache cleared");
        } catch (Exception e) {
            log.error("Failed to refresh configs", e);
        }
    }

    public Flux<Config> listConfigs(String namespace) {
        return Flux.fromIterable(
                configMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Config>()
                                .eq(namespace != null, "namespace", namespace)
                                .orderByDesc("created_at")
                )
        );
    }

    public Mono<Config> getConfigById(String configId) {
        return Mono.justOrEmpty(
                configMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Config>()
                                .eq("config_id", configId)
                )
        );
    }

    public Mono<Void> deleteConfig(String configId) {
        return Mono.fromRunnable(() -> {
            Config config = configMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Config>()
                            .eq("config_id", configId)
            );
            if (config != null) {
                config.setEnabled(false);
                configMapper.updateById(config);
                configCache.invalidateAll();
                log.info("Config disabled: {}", configId);
            }
        });
    }

    public boolean isValidStatusTransition(Config.ConfigStatus currentStatus, Config.ConfigStatus targetStatus) {
        if (currentStatus == null || targetStatus == null) {
            return false;
        }
        Set<Config.ConfigStatus> allowedTransitions = Config.VALID_TRANSITIONS.get(currentStatus);
        return allowedTransitions != null && allowedTransitions.contains(targetStatus);
    }

    public Mono<Config> transitionStatus(String configId, Config.ConfigStatus targetStatus) {
        return Mono.fromCallable(() -> {
            Config config = configMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Config>()
                            .eq("config_id", configId)
            );

            if (config == null) {
                throw new BusinessException("Config not found: " + configId);
            }

            Config.ConfigStatus currentStatus;
            try {
                currentStatus = Config.ConfigStatus.valueOf(config.getStatus());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("Invalid current status: " + config.getStatus());
            }

            if (!isValidStatusTransition(currentStatus, targetStatus)) {
                throw new BusinessException(
                        String.format("Invalid status transition from %s to %s", currentStatus, targetStatus));
            }

            config.setStatus(targetStatus.name());
            configMapper.updateById(config);

            if (targetStatus == Config.ConfigStatus.PUBLISHED) {
                configCache.invalidateAll();
            }

            log.info("Config {} status transitioned from {} to {}", configId, currentStatus, targetStatus);
            return config;
        });
    }
}
