package com.observability.config.service;

import com.observability.common.entity.ConfigEntity;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

public interface ConfigQueryService {

    Mono<Map<String, Object>> loadConfig(String namespace);

    Mono<Optional<ConfigEntity>> getLatestConfig(String namespace);

    Mono<Map<String, Object>> getConfigValue(String namespace, String key);

    Mono<Void> refreshConfig(String namespace);
}
