package com.scheduler.data.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scheduler.common.exception.BusinessException;
import com.scheduler.data.cache.CacheManager;
import com.scheduler.persistence.entity.ConfigDefinition;
import com.scheduler.persistence.mapper.ConfigDefinitionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ConfigRepository {

    private final ConfigDefinitionMapper configMapper;
    private final CacheManager cacheManager;
    private static final String CACHE_NAME = "configs";

    public ConfigDefinition create(ConfigDefinition config) {
        Integer maxVersion = configMapper.selectList(new LambdaQueryWrapper<ConfigDefinition>()
                        .eq(ConfigDefinition::getConfigId, config.getConfigId()))
                .stream()
                .mapToInt(ConfigDefinition::getVersion)
                .max()
                .orElse(0);
        config.setVersion(maxVersion + 1);
        config.setAppliedAt(Instant.now());
        configMapper.insert(config);
        cacheManager.invalidate(CACHE_NAME, config.getConfigId());
        log.info("Created config: {} version {}", config.getConfigId(), config.getVersion());
        return config;
    }

    public ConfigDefinition findLatest(String configId) {
        return cacheManager.get(CACHE_NAME, configId, id ->
                configMapper.findLatestByConfigId(id)
                        .orElseThrow(() -> BusinessException.notFound("Config not found: " + id))
        );
    }

    public Optional<ConfigDefinition> findByVersion(String configId, int version) {
        return configMapper.findByConfigIdAndVersion(configId, version);
    }

    public List<ConfigDefinition> findEnabledByNamespace(String namespace) {
        return configMapper.findEnabledByNamespace(namespace);
    }

    public ConfigDefinition update(String configId, ConfigDefinition config) {
        ConfigDefinition existing = findLatest(configId);
        config.setId(existing.getId());
        config.setConfigId(configId);
        config.setVersion(existing.getVersion() + 1);
        config.setAppliedAt(Instant.now());
        configMapper.insert(config);
        cacheManager.invalidate(CACHE_NAME, configId);
        return config;
    }

    public void delete(String configId) {
        List<ConfigDefinition> configs = configMapper.selectList(new LambdaQueryWrapper<ConfigDefinition>()
                .eq(ConfigDefinition::getConfigId, configId));
        configs.forEach(cfg -> configMapper.deleteById(cfg.getId()));
        cacheManager.invalidate(CACHE_NAME, configId);
    }
}
