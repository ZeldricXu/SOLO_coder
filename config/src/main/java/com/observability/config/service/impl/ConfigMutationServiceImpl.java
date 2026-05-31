package com.observability.config.service.impl;

import com.observability.common.entity.ConfigEntity;
import com.observability.common.enums.ConfigSource;
import com.observability.common.util.IdGenerator;
import com.observability.config.cache.ConfigCache;
import com.observability.config.listener.ConfigChangeListenerManager;
import com.observability.config.service.ConfigMutationService;
import com.observability.dal.repository.ConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigMutationServiceImpl implements ConfigMutationService {

    private final ConfigRepository configRepository;
    private final ConfigCache configCache;
    private final ConfigChangeListenerManager listenerManager;

    @Override
    public Mono<ConfigEntity> saveConfig(String namespace, Map<String, Object> parameters, String source) {
        return Mono.fromCallable(() -> {
            int version = configRepository.findLatestByNamespace(namespace)
                    .map(config -> config.getVersion() + 1)
                    .orElse(1);

            ConfigEntity config = new ConfigEntity();
            config.setConfigId(IdGenerator.generateConfigId());
            config.setNamespace(namespace);
            config.setVersion(version);
            config.setEnabled(true);
            config.setAppliedAt(LocalDateTime.now());
            config.setSource(source != null ? source : ConfigSource.DATABASE.getCode());

            try {
                java.lang.reflect.Field field = ConfigEntity.class.getDeclaredField("parameters");
                field.setAccessible(true);
                field.set(config, parameters);
            } catch (Exception e) {
                log.error("Failed to set parameters for config: {}", namespace, e);
            }

            ConfigEntity saved = configRepository.save(config);

            configCache.invalidate(namespace);
            listenerManager.notifyListeners(namespace, parameters);

            log.info("Config saved - namespace: {}, version: {}", namespace, version);
            return saved;
        });
    }

    @Override
    public void addListener(String namespace, Consumer<Map<String, Object>> listener) {
        listenerManager.addListener(namespace, listener);
    }

    @Override
    public void removeListener(String namespace, Consumer<Map<String, Object>> listener) {
        listenerManager.removeListener(namespace, listener);
    }
}
