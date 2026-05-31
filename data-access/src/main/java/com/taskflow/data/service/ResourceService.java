package com.taskflow.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskflow.common.exception.ResourceNotFoundException;
import com.taskflow.common.model.PageResult;
import com.taskflow.common.utils.JsonUtils;
import com.taskflow.data.entity.ResourceEntity;
import com.taskflow.data.mapper.ResourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ResourceService {

    private final ResourceMapper resourceMapper;

    public ResourceEntity create(ResourceEntity entity) {
        resourceMapper.insert(entity);
        return entity;
    }

    public ResourceEntity getById(String tenantId, String resourceId) {
        ResourceEntity entity = resourceMapper.selectByTenantAndId(tenantId, resourceId);
        if (entity == null) {
            throw new ResourceNotFoundException("Resource", resourceId);
        }
        deserializeFields(entity);
        return entity;
    }

    public PageResult<ResourceEntity> list(String tenantId, String type, int page, int size) {
        LambdaQueryWrapper<ResourceEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ResourceEntity::getTenantId, tenantId);
        if (type != null && !type.isEmpty()) {
            wrapper.eq(ResourceEntity::getType, type);
        }
        wrapper.orderByDesc(ResourceEntity::getCreatedAt);

        Page<ResourceEntity> pageResult = resourceMapper.selectPage(Page.of(page, size), wrapper);
        pageResult.getRecords().forEach(this::deserializeFields);

        return PageResult.of(pageResult.getRecords(), pageResult.getTotal(), page, size);
    }

    public ResourceEntity update(ResourceEntity entity) {
        serializeFields(entity);
        resourceMapper.updateById(entity);
        return getById(entity.getTenantId(), entity.getResourceId());
    }

    public void delete(String tenantId, String resourceId) {
        ResourceEntity entity = getById(tenantId, resourceId);
        resourceMapper.deleteById(entity.getId());
    }

    public List<ResourceEntity> findByType(String tenantId, String type) {
        List<ResourceEntity> entities = resourceMapper.selectByTenantAndType(tenantId, type);
        entities.forEach(this::deserializeFields);
        return entities;
    }

    public List<ResourceEntity> findByStatus(String tenantId, String status) {
        List<ResourceEntity> entities = resourceMapper.selectByTenantAndStatus(tenantId, status);
        entities.forEach(this::deserializeFields);
        return entities;
    }

    private void serializeFields(ResourceEntity entity) {
        if (entity.getAttributesMap() != null) {
            entity.setAttributes(JsonUtils.toJson(entity.getAttributesMap()));
        }
        if (entity.getLabelsMap() != null) {
            entity.setLabels(JsonUtils.toJson(entity.getLabelsMap()));
        }
    }

    private void deserializeFields(ResourceEntity entity) {
        if (entity.getAttributes() != null) {
            entity.setAttributesMap(JsonUtils.fromJson(entity.getAttributes(), Map.class));
        }
        if (entity.getLabels() != null) {
            entity.setLabelsMap(JsonUtils.fromJson(entity.getLabels(), Map.class));
        }
    }
}
