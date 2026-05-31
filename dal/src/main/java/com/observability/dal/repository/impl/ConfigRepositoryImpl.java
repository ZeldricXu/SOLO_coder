package com.observability.dal.repository.impl;

import com.observability.common.entity.ConfigEntity;
import com.observability.dal.mapper.ConfigMapper;
import com.observability.dal.repository.ConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ConfigRepositoryImpl implements ConfigRepository {

    private final ConfigMapper configMapper;

    @Override
    public ConfigEntity save(ConfigEntity entity) {
        configMapper.insert(entity);
        return entity;
    }

    @Override
    public Optional<ConfigEntity> findLatestByNamespace(String namespace) {
        return Optional.ofNullable(configMapper.findLatestByNamespace(namespace));
    }

    @Override
    public Optional<ConfigEntity> findLatestByConfigId(String configId) {
        return Optional.ofNullable(configMapper.findLatestByConfigId(configId));
    }

    @Override
    public List<ConfigEntity> findAllByNamespace(String namespace) {
        return configMapper.findAllByNamespace(namespace);
    }
}
