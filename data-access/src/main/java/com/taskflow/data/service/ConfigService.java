package com.taskflow.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskflow.common.exception.ResourceNotFoundException;
import com.taskflow.common.model.PageResult;
import com.taskflow.common.utils.JsonUtils;
import com.taskflow.data.entity.ConfigEntity;
import com.taskflow.data.mapper.ConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ConfigService {

    private final ConfigMapper configMapper;

    public ConfigEntity create(ConfigEntity entity) {
        if (entity.getVersion() == null) {
            entity.setVersion(1);
        }
        entity.setAppliedAt(LocalDateTime.now());
        serializeFields(entity);
        configMapper.insert(entity);
        deserializeFields(entity);
        return entity;
    }

    public ConfigEntity getLatest(String tenantId, String configId) {
        ConfigEntity entity = configMapper.selectLatestByTenantAndId(tenantId, configId);
        if (entity == null) {
            throw new ResourceNotFoundException("Config", configId);
        }
        deserializeFields(entity);
        return entity;
    }

    public ConfigEntity getEnabled(String tenantId, String namespace, String configId) {
        ConfigEntity entity = configMapper.selectEnabledByNamespace(tenantId, namespace, configId);
        if (entity == null) {
            throw new ResourceNotFoundException("Config", configId);
        }
        deserializeFields(entity);
        return entity;
    }

    public ConfigEntity createNewVersion(String tenantId, String configId, Map<String, Object> parameters, String description) {
        ConfigEntity latest = getLatest(tenantId, configId);
        ConfigEntity newVersion = new ConfigEntity();
        newVersion.setTenantId(tenantId);
        newVersion.setConfigId(configId);
        newVersion.setNamespace(latest.getNamespace());
        newVersion.setVersion(latest.getVersion() + 1);
        newVersion.setParametersMap(parameters);
        newVersion.setEnabled(true);
        newVersion.setAppliedAt(LocalDateTime.now());
        newVersion.setDescription(description);
        return create(newVersion);
    }

    public PageResult<ConfigEntity> listVersions(String tenantId, String configId, int page, int size) {
        LambdaQueryWrapper<ConfigEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConfigEntity::getTenantId, tenantId)
                .eq(ConfigEntity::getConfigId, configId)
                .orderByDesc(ConfigEntity::getVersion);

        Page<ConfigEntity> pageResult = configMapper.selectPage(Page.of(page, size), wrapper);
        pageResult.getRecords().forEach(this::deserializeFields);

        return PageResult.of(pageResult.getRecords(), pageResult.getTotal(), page, size);
    }

    public void toggleEnabled(String tenantId, String configId, boolean enabled) {
        ConfigEntity entity = getLatest(tenantId, configId);
        entity.setEnabled(enabled);
        configMapper.updateById(entity);
    }

    private void serializeFields(ConfigEntity entity) {
        if (entity.getParametersMap() != null) {
            entity.setParameters(JsonUtils.toJson(entity.getParametersMap()));
        }
    }

    private void deserializeFields(ConfigEntity entity) {
        if (entity.getParameters() != null) {
            entity.setParametersMap(JsonUtils.fromJson(entity.getParameters(), Map.class));
        }
    }
}
