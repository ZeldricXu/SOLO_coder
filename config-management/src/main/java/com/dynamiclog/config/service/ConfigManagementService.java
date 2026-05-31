package com.dynamiclog.config.service;

import com.dynamiclog.common.entity.Config;
import com.dynamiclog.common.exception.BusinessException;
import com.dynamiclog.common.exception.ResourceNotFoundException;
import com.dynamiclog.common.util.IdGenerator;
import com.dynamiclog.persistence.mapper.ConfigMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigManagementService {

    private final ConfigMapper configMapper;

    private final Cache<String, Config> configCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(10000)
            .build();

    public Mono<Config> publishConfig(String dataId, String namespace, String group, String content, String description) {
        return Mono.fromCallable(() -> {
            Integer currentMaxVersion = configMapper.getMaxVersion(dataId, namespace);
            int newVersion = currentMaxVersion != null ? currentMaxVersion + 1 : 1;

            Config config = new Config();
            config.setId(IdGenerator.generateId("cfg"));
            config.setConfigId(IdGenerator.generateId("cfgid"));
            config.setDataId(dataId);
            config.setNamespace(namespace);
            config.setGroup(group != null ? group : "DEFAULT_GROUP");
            config.setVersion(newVersion);
            config.setContent(content);
            config.setContentType("json");
            config.setEnabled(true);
            config.setDescription(description);
            config.setAppliedAt(LocalDateTime.now());
            config.setRollbackAvailable(true);
            config.setPreviousVersion(currentMaxVersion);

            configMapper.insert(config);
            invalidateCache(dataId, namespace);

            log.info("Config published: dataId={}, namespace={}, version={}", dataId, namespace, newVersion);
            return config;
        });
    }

    public Mono<Config> getConfig(String dataId, String namespace) {
        return Mono.fromCallable(() -> {
            String cacheKey = cacheKey(dataId, namespace);
            Config cached = configCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }

            Config config = configMapper.findLatestByDataIdAndNamespace(dataId, namespace)
                    .orElseThrow(() -> new ResourceNotFoundException("Config", dataId));

            configCache.put(cacheKey, config);
            return config;
        });
    }

    public Mono<Config> getConfigByVersion(String dataId, String namespace, int version) {
        return Mono.fromCallable(() ->
                configMapper.findByDataIdAndNamespaceAndVersion(dataId, namespace, version)
                        .orElseThrow(() -> new ResourceNotFoundException("Config", dataId + ":" + version))
        );
    }

    public Flux<Config> getConfigHistory(String dataId, String namespace) {
        return Mono.fromCallable(() -> configMapper.findByNamespace(namespace).stream()
                        .filter(c -> c.getDataId().equals(dataId))
                        .toList())
                .flatMapMany(Flux::fromIterable);
    }

    public Flux<Config> getConfigsByNamespace(String namespace) {
        return Mono.fromCallable(() -> configMapper.findByNamespace(namespace))
                .flatMapMany(Flux::fromIterable);
    }

    public Mono<Config> rollbackToVersion(String dataId, String namespace, int targetVersion) {
        return getConfigByVersion(dataId, namespace, targetVersion)
                .flatMap(targetConfig -> publishConfig(
                        dataId,
                        namespace,
                        targetConfig.getGroup(),
                        targetConfig.getContent(),
                        "Rollback to version " + targetVersion
                ))
                .doOnNext(c -> log.info("Config rolled back: dataId={}, namespace={}, toVersion={}",
                        dataId, namespace, targetVersion));
    }

    public Mono<Config> rollbackToPrevious(String dataId, String namespace) {
        return getConfig(dataId, namespace)
                .flatMap(current -> {
                    if (current.getPreviousVersion() == null) {
                        throw new BusinessException(400, "No previous version available");
                    }
                    return rollbackToVersion(dataId, namespace, current.getPreviousVersion());
                });
    }

    public Mono<Void> deleteConfig(String dataId, String namespace) {
        return Mono.fromRunnable(() -> {
            List<Config> configs = configMapper.findByNamespace(namespace).stream()
                    .filter(c -> c.getDataId().equals(dataId))
                    .toList();

            for (Config config : configs) {
                config.setDeleted(true);
                configMapper.updateById(config);
            }
            invalidateCache(dataId, namespace);
            log.info("Config deleted: dataId={}, namespace={}", dataId, namespace);
        });
    }

    public Mono<List<Config>> listAllVersions(String dataId, String namespace) {
        return getConfigHistory(dataId, namespace).collectList();
    }

    public Mono<Boolean> validateConfig(String content, String contentType) {
        return Mono.fromCallable(() -> {
            if ("json".equals(contentType)) {
                try {
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
                    return true;
                } catch (Exception e) {
                    throw new BusinessException(400, "INVALID_CONFIG", "Invalid JSON format: " + e.getMessage());
                }
            }
            return true;
        });
    }

    private String cacheKey(String dataId, String namespace) {
        return namespace + ":" + dataId;
    }

    private void invalidateCache(String dataId, String namespace) {
        configCache.invalidate(cacheKey(dataId, namespace));
    }
}
