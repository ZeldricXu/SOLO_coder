package com.taskflow.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskflow.common.exception.ResourceNotFoundException;
import com.taskflow.common.model.PageResult;
import com.taskflow.data.entity.RunInstanceEntity;
import com.taskflow.data.mapper.RunInstanceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RunInstanceService {

    private final RunInstanceMapper runInstanceMapper;

    public RunInstanceEntity create(RunInstanceEntity entity) {
        entity.setStartedAt(LocalDateTime.now());
        entity.setProgress(0.0);
        entity.setRetryCount(0);
        runInstanceMapper.insert(entity);
        return entity;
    }

    public RunInstanceEntity getById(String tenantId, String runId) {
        RunInstanceEntity entity = runInstanceMapper.selectByTenantAndId(tenantId, runId);
        if (entity == null) {
            throw new ResourceNotFoundException("RunInstance", runId);
        }
        return entity;
    }

    public PageResult<RunInstanceEntity> listByEntityId(String tenantId, String entityId, int page, int size) {
        LambdaQueryWrapper<RunInstanceEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RunInstanceEntity::getTenantId, tenantId)
                .eq(RunInstanceEntity::getEntityId, entityId)
                .orderByDesc(RunInstanceEntity::getStartedAt);

        Page<RunInstanceEntity> pageResult = runInstanceMapper.selectPage(Page.of(page, size), wrapper);
        return PageResult.of(pageResult.getRecords(), pageResult.getTotal(), page, size);
    }

    public List<RunInstanceEntity> getByPhase(String tenantId, String phase) {
        return runInstanceMapper.selectByPhase(tenantId, phase);
    }

    public List<RunInstanceEntity> getByEntityId(String tenantId, String entityId, int limit) {
        return runInstanceMapper.selectByEntityId(tenantId, entityId, limit);
    }

    public void updateProgress(String runId, String phase, Double progress) {
        runInstanceMapper.updateProgress(runId, phase, progress, LocalDateTime.now());
    }

    public void markCompleted(String runId) {
        runInstanceMapper.markCompleted(runId, LocalDateTime.now());
    }

    public void markFailed(String runId, Double progress, String errorDetail) {
        runInstanceMapper.markFailed(runId, progress, errorDetail, LocalDateTime.now());
    }

    public void incrementRetry(String runId) {
        RunInstanceEntity entity = runInstanceMapper.selectById(runId);
        if (entity != null) {
            entity.setRetryCount(entity.getRetryCount() + 1);
            runInstanceMapper.updateById(entity);
        }
    }
}
