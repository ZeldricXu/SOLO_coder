package com.observability.config.service;

import com.observability.common.entity.ConfigEntity;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Consumer;

public interface ConfigMutationService {

    Mono<ConfigEntity> saveConfig(String namespace, Map<String, Object> parameters, String source);

    void addListener(String namespace, Consumer<Map<String, Object>> listener);

    void removeListener(String namespace, Consumer<Map<String, Object>> listener);
}
