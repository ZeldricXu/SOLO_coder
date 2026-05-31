package com.logmanager.service;

import com.logmanager.domain.model.ConfigDefinition;
import com.logmanager.common.enums.ConfigSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;

public interface ConfigService {
    Mono<ConfigDefinition> createConfig(String namespace, String configId, Map<String, Object> parameters, ConfigSource source);
    Mono<ConfigDefinition> updateConfig(String id, Map<String, Object> parameters);
    Mono<ConfigDefinition> getConfig(String id);
    Mono<ConfigDefinition> getConfigByNamespaceAndKey(String namespace, String key);
    Flux<ConfigDefinition> getConfigsByNamespace(String namespace);
    Flux<ConfigDefinition> getAllEnabledConfigs();
    Mono<Void> deleteConfig(String id);
    Mono<Void> reloadConfigs();
    Mono<Map<String, Object>> getMergedConfig(String namespace);
}
