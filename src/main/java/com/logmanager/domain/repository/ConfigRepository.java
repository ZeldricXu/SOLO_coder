package com.logmanager.domain.repository;

import com.logmanager.domain.model.ConfigDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ConfigRepository {
    Mono<ConfigDefinition> save(ConfigDefinition config);
    Mono<ConfigDefinition> findById(String configId);
    Mono<ConfigDefinition> findByNamespaceAndKey(String namespace, String key);
    Flux<ConfigDefinition> findByNamespace(String namespace);
    Mono<Void> deleteById(String configId);
    Flux<ConfigDefinition> findAllEnabled();
}
