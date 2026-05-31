package com.logmanager.service.impl;

import com.logmanager.common.enums.ConfigSource;
import com.logmanager.domain.event.DomainEvent;
import com.logmanager.domain.event.EventPublisher;
import com.logmanager.domain.model.ConfigDefinition;
import com.logmanager.domain.repository.ConfigRepository;
import com.logmanager.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigServiceImpl implements ConfigService {

    private final ConfigRepository configRepository;
    private final EventPublisher eventPublisher;

    @Override
    @CacheEvict(value = "configs", allEntries = true)
    public Mono<ConfigDefinition> createConfig(String namespace, String configId, Map<String, Object> parameters, ConfigSource source) {
        ConfigDefinition config = new ConfigDefinition();
        config.setId(UUID.randomUUID().toString());
        config.setConfigId(configId);
        config.setNamespace(namespace);
        config.setVersion(1);
        config.setParameters(parameters);
        config.setEnabled(true);
        config.setAppliedAt(Instant.now());
        config.setSource(source);
        config.setCreatedAt(Instant.now());
        config.setUpdatedAt(Instant.now());

        return configRepository.save(config)
                .doOnSuccess(saved -> {
                    log.info("Config created: {} in namespace {}", configId, namespace);
                    eventPublisher.publish(new DomainEvent("config.created", saved.getId(), "config"));
                });
    }

    @Override
    @CacheEvict(value = "configs", allEntries = true)
    public Mono<ConfigDefinition> updateConfig(String id, Map<String, Object> parameters) {
        return configRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Config not found: " + id)))
                .flatMap(config -> {
                    config.setParameters(parameters);
                    config.setVersion(config.getVersion() + 1);
                    config.setUpdatedAt(Instant.now());
                    return configRepository.save(config);
                })
                .doOnSuccess(updated -> {
                    log.info("Config updated: {}", id);
                    eventPublisher.publish(new DomainEvent("config.updated", updated.getId(), "config"));
                });
    }

    @Override
    @Cacheable(value = "configs", key = "#id")
    public Mono<ConfigDefinition> getConfig(String id) {
        return configRepository.findById(id);
    }

    @Override
    @Cacheable(value = "configs", key = "#namespace + ':' + #key")
    public Mono<ConfigDefinition> getConfigByNamespaceAndKey(String namespace, String key) {
        return configRepository.findByNamespaceAndKey(namespace, key);
    }

    @Override
    public Flux<ConfigDefinition> getConfigsByNamespace(String namespace) {
        return configRepository.findByNamespace(namespace);
    }

    @Override
    public Flux<ConfigDefinition> getAllEnabledConfigs() {
        return configRepository.findAllEnabled();
    }

    @Override
    @CacheEvict(value = "configs", allEntries = true)
    public Mono<Void> deleteConfig(String id) {
        return configRepository.deleteById(id)
                .doOnSuccess(v -> {
                    log.info("Config deleted: {}", id);
                    eventPublisher.publish(new DomainEvent("config.deleted", id, "config"));
                });
    }

    @Override
    @CacheEvict(value = "configs", allEntries = true)
    public Mono<Void> reloadConfigs() {
        log.info("Reloading all configurations");
        eventPublisher.publish(new DomainEvent("config.reload", "all", "config"));
        return Mono.empty();
    }

    @Override
    public Mono<Map<String, Object>> getMergedConfig(String namespace) {
        return configRepository.findByNamespace(namespace)
                .collectMap(ConfigDefinition::getConfigId, ConfigDefinition::getParameters)
                .map(configMap -> {
                    Map<String, Object> merged = new HashMap<>();
                    configMap.values().forEach(merged::putAll);
                    return merged;
                });
    }
}
