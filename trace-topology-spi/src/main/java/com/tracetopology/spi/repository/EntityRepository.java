package com.tracetopology.spi.repository;

import com.tracetopology.domain.entity.Config;
import com.tracetopology.domain.entity.Entity;
import com.tracetopology.domain.entity.RunInstance;
import com.tracetopology.common.result.PageResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface EntityRepository {

    Entity save(Entity entity);

    void saveBatch(List<Entity> entities);

    Optional<Entity> findById(String id);

    PageResult<Entity> findByType(String type, int pageNum, int pageSize);

    void deleteById(String id);

    RunInstance saveRunInstance(RunInstance runInstance);

    Optional<RunInstance> findRunInstanceById(String runId);

    List<RunInstance> findRunInstancesByEntityId(String entityId);

    Config saveConfig(Config config);

    Optional<Config> findConfigByNamespace(String namespace);

    List<Config> findConfigVersions(String configId);

    Optional<Config> findConfigByIdAndVersion(String configId, int version);

    Map<String, Object> findConfigParameters(String namespace);
}
