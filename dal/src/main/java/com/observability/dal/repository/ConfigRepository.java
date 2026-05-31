package com.observability.dal.repository;

import com.observability.common.entity.ConfigEntity;

import java.util.List;
import java.util.Optional;

public interface ConfigRepository {

    ConfigEntity save(ConfigEntity entity);

    Optional<ConfigEntity> findLatestByNamespace(String namespace);

    Optional<ConfigEntity> findLatestByConfigId(String configId);

    List<ConfigEntity> findAllByNamespace(String namespace);
}
