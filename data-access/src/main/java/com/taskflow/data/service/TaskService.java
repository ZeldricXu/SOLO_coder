package com.taskflow.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskflow.common.exception.ResourceNotFoundException;
import com.taskflow.common.model.PageResult;
import com.taskflow.common.utils.JsonUtils;
import com.taskflow.data.entity.TaskEntity;
import com.taskflow.data.mapper.TaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskMapper taskMapper;

    public TaskEntity create(TaskEntity entity) {
        if (entity.getMaxRetry() == null) {
            entity.setMaxRetry(3);
        }
        if (entity.getTimeoutSeconds() == null) {
            entity.setTimeoutSeconds(300);
        }
        if (entity.getStatus() == null) {
            entity.setStatus("active");
        }
        serializeFields(entity);
        taskMapper.insert(entity);
        deserializeFields(entity);
        return entity;
    }

    public TaskEntity getById(String tenantId, String taskId) {
        TaskEntity entity = taskMapper.selectByTenantAndId(tenantId, taskId);
        if (entity == null) {
            throw new ResourceNotFoundException("Task", taskId);
        }
        deserializeFields(entity);
        return entity;
    }

    public PageResult<TaskEntity> list(String tenantId, String status, int page, int size) {
        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskEntity::getTenantId, tenantId);
        if (status != null && !status.isEmpty()) {
            wrapper.eq(TaskEntity::getStatus, status);
        }
        wrapper.orderByDesc(TaskEntity::getCreatedAt);

        Page<TaskEntity> pageResult = taskMapper.selectPage(Page.of(page, size), wrapper);
        pageResult.getRecords().forEach(this::deserializeFields);

        return PageResult.of(pageResult.getRecords(), pageResult.getTotal(), page, size);
    }

    public List<TaskEntity> getActiveTasks(String tenantId) {
        return taskMapper.selectActiveTasks(tenantId);
    }

    public List<TaskEntity> getTasksToRun(String tenantId, LocalDateTime now) {
        return taskMapper.selectTasksToRun(tenantId, now);
    }

    public void updateRunTimes(String taskId, LocalDateTime lastRunTime, LocalDateTime nextRunTime) {
        taskMapper.updateRunTimes(taskId, lastRunTime, nextRunTime, LocalDateTime.now());
    }

    public void updateStatus(String taskId, String status) {
        taskMapper.updateStatus(taskId, status, LocalDateTime.now());
    }

    public TaskEntity update(TaskEntity entity) {
        serializeFields(entity);
        taskMapper.updateById(entity);
        return getById(entity.getTenantId(), entity.getTaskId());
    }

    public void delete(String tenantId, String taskId) {
        TaskEntity entity = getById(tenantId, taskId);
        taskMapper.deleteById(entity.getId());
    }

    private void serializeFields(TaskEntity entity) {
        if (entity.getParametersMap() != null) {
            entity.setParameters(JsonUtils.toJson(entity.getParametersMap()));
        }
    }

    private void deserializeFields(TaskEntity entity) {
        if (entity.getParameters() != null) {
            entity.setParametersMap(JsonUtils.fromJson(entity.getParameters(), Map.class));
        }
    }
}
