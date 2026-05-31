package com.observability.dal.repository;

import com.observability.common.entity.ResourceEntity;

import java.util.Optional;

public interface ResourceRepository {

    ResourceEntity save(ResourceEntity entity);

    Optional<ResourceEntity> findByResourceId(String resourceId);

    void deleteByResourceId(String resourceId);

    ResourceEntity updateStatus(String resourceId, String status);

    boolean existsByResourceId(String resourceId);
}
