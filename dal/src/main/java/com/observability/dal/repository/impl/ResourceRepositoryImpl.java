package com.observability.dal.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.observability.common.entity.ResourceEntity;
import com.observability.common.exception.BusinessException;
import com.observability.dal.mapper.ResourceMapper;
import com.observability.dal.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ResourceRepositoryImpl implements ResourceRepository {

    private final ResourceMapper resourceMapper;

    @Override
    public ResourceEntity save(ResourceEntity entity) {
        resourceMapper.insert(entity);
        return entity;
    }

    @Override
    public Optional<ResourceEntity> findByResourceId(String resourceId) {
        return Optional.ofNullable(
                resourceMapper.selectOne(
                        new QueryWrapper<ResourceEntity>().eq("resource_id", resourceId)
                )
        );
    }

    @Override
    public void deleteByResourceId(String resourceId) {
        resourceMapper.delete(
                new QueryWrapper<ResourceEntity>().eq("resource_id", resourceId)
        );
    }

    @Override
    public ResourceEntity updateStatus(String resourceId, String status) {
        ResourceEntity resource = findByResourceId(resourceId)
                .orElseThrow(() -> BusinessException.notFound("Resource not found: " + resourceId));
        resource.setStatus(status);
        resourceMapper.updateById(resource);
        return resource;
    }

    @Override
    public boolean existsByResourceId(String resourceId) {
        Long count = resourceMapper.selectCount(
                new QueryWrapper<ResourceEntity>().eq("resource_id", resourceId)
        );
        return count != null && count > 0;
    }
}
