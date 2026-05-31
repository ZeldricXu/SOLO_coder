package com.tracetopology.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracetopology.common.result.PageResult;
import com.tracetopology.domain.entity.Config;
import com.tracetopology.domain.entity.Entity;
import com.tracetopology.domain.entity.RunInstance;
import com.tracetopology.infrastructure.persistence.entity.ConfigPO;
import com.tracetopology.infrastructure.persistence.entity.EntityPO;
import com.tracetopology.infrastructure.persistence.entity.RunInstancePO;
import com.tracetopology.infrastructure.persistence.mapper.ConfigMapper;
import com.tracetopology.infrastructure.persistence.mapper.EntityMapper;
import com.tracetopology.infrastructure.persistence.mapper.RunInstanceMapper;
import com.tracetopology.spi.repository.EntityRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public class EntityRepositoryImpl implements EntityRepository {

    private final EntityMapper entityMapper;
    private final ConfigMapper configMapper;
    private final RunInstanceMapper runInstanceMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Entity save(Entity entity) {
        EntityPO po = EntityPO.fromDomain(entity);
        entityMapper.insertOrUpdate(po);
        return entity;
    }

    @Override
    public void saveBatch(List<Entity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        List<EntityPO> pos = entities.stream()
                .map(EntityPO::fromDomain)
                .collect(Collectors.toList());
        for (EntityPO po : pos) {
            entityMapper.insertOrUpdate(po);
        }
        log.debug("批量保存实体: count={}", entities.size());
    }

    @Override
    public Optional<Entity> findById(String id) {
        return Optional.ofNullable(entityMapper.selectById(id))
                .map(EntityPO::toDomain);
    }

    @Override
    public PageResult<Entity> findByType(String type, int pageNum, int pageSize) {
        LambdaQueryWrapper<EntityPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EntityPO::getType, type)
                .orderByDesc(EntityPO::getCreatedAt);

        IPage<EntityPO> page = entityMapper.selectPage(
                new Page<>(pageNum, pageSize), wrapper);

        List<Entity> records = page.getRecords().stream()
                .map(EntityPO::toDomain)
                .collect(Collectors.toList());

        return PageResult.of(records, page.getTotal(), pageNum, pageSize);
    }

    @Override
    public void deleteById(String id) {
        entityMapper.deleteById(id);
    }

    @Override
    public RunInstance saveRunInstance(RunInstance runInstance) {
        RunInstancePO po = RunInstancePO.fromDomain(runInstance);
        runInstanceMapper.insertOrUpdate(po);
        return runInstance;
    }

    @Override
    public Optional<RunInstance> findRunInstanceById(String runId) {
        return Optional.ofNullable(runInstanceMapper.selectById(runId))
                .map(RunInstancePO::toDomain);
    }

    @Override
    public List<RunInstance> findRunInstancesByEntityId(String entityId) {
        return runInstanceMapper.findByEntityId(entityId).stream()
                .map(RunInstancePO::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public Config saveConfig(Config config) {
        ConfigPO po = ConfigPO.fromDomain(config);
        configMapper.insert(po);
        return config;
    }

    @Override
    public Optional<Config> findConfigByNamespace(String namespace) {
        return Optional.ofNullable(configMapper.findLatestByNamespace(namespace))
                .map(ConfigPO::toDomain);
    }

    @Override
    public List<Config> findConfigVersions(String configId) {
        return configMapper.findVersions(configId).stream()
                .map(ConfigPO::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Config> findConfigByIdAndVersion(String configId, int version) {
        return Optional.ofNullable(configMapper.findByIdAndVersion(configId, version))
                .map(ConfigPO::toDomain);
    }

    @Override
    @SneakyThrows
    public Map<String, Object> findConfigParameters(String namespace) {
        ConfigPO po = configMapper.findLatestByNamespace(namespace);
        if (po != null) {
            return objectMapper.readValue(po.getParameters(), new TypeReference<Map<String, Object>>() {});
        }
        return Map.of(
                "poolSize", 10,
                "timeoutSeconds", 30,
                "retries", 3
        );
    }
}
