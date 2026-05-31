package com.observability.config.service.impl;

import com.observability.common.entity.ConfigEntity;
import com.observability.config.cache.ConfigCache;
import com.observability.config.loader.ConfigLoaderManager;
import com.observability.config.service.ConfigQueryService;
import com.observability.dal.repository.ConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigQueryServiceImpl implements ConfigQueryService {

    private final ConfigCache configCache;
    private final ConfigLoaderManager configLoaderManager;
    private final ConfigRepository configRepository;

    @Override
    public Mono<Map<String, Object>> loadConfig(String namespace) {
        return Mono.fromCallable(() -> {
            Optional<Map<String, Object>> cached = configCache.get(namespace);
            if (cached.isPresent()) {
                log.debug("Cache hit for namespace: {}", namespace);
                return cached.get();
            }

            Map<String, Object> config = configLoaderManager.loadFromAllSources(namespace);
            configCache.put(namespace, config);

            return config;
        });
    }

    @Override
    public Mono<Optional<ConfigEntity>> getLatestConfig(String namespace) {
        return Mono.fromCallable(() ->
                configRepository.findLatestByNamespace(namespace)
        );
    }

    @Override
    public Mono<Map<String, Object>> getConfigValue(String namespace, String key) {
        return loadConfig(namespace)
                .map(config -> {
                    Map<String, Object> result = new HashMap<>();
                    if (config.containsKey(key)) {
                        result.put(key, config.get(key));
                    }
                    return result;
                });
    }

    @Override
    public Mono<Void> refreshConfig(String namespace) {
        return Mono.fromRunnable(() -> {
            configCache.invalidate(namespace);
            loadConfig(namespace).subscribe(
                    config -> log.info("Config refreshed for namespace: {}", namespace),
                    error -> log.error("Failed to refresh config for namespace: {}", namespace, error)
            );
        });
    }
}
