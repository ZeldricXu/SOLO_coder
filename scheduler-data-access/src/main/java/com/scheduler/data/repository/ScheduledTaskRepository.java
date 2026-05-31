package com.scheduler.data.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.scheduler.common.exception.BusinessException;
import com.scheduler.data.cache.CacheManager;
import com.scheduler.persistence.entity.ScheduledTask;
import com.scheduler.persistence.mapper.ScheduledTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ScheduledTaskRepository {

    private final ScheduledTaskMapper taskMapper;
    private final CacheManager cacheManager;
    private static final String CACHE_NAME = "tasks";

    public ScheduledTask create(ScheduledTask task) {
        task.setTaskId("task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        task.setStatus("PENDING");
        taskMapper.insert(task);
        log.info("Created scheduled task: {}", task.getTaskId());
        return task;
    }

    public ScheduledTask findById(String taskId) {
        return cacheManager.get(CACHE_NAME, taskId, id -> {
            ScheduledTask task = taskMapper.selectOne(new LambdaQueryWrapper<ScheduledTask>()
                    .eq(ScheduledTask::getTaskId, id));
            if (task == null) {
                throw BusinessException.notFound("Task not found: " + id);
            }
            return task;
        });
    }

    public ScheduledTask update(ScheduledTask task) {
        try {
            int updated = taskMapper.updateById(task);
            if (updated == 0) {
                throw BusinessException.conflict(task.getTaskId());
            }
            cacheManager.invalidate(CACHE_NAME, task.getTaskId());
            log.debug("Updated task: {}", task.getTaskId());
            return findById(task.getTaskId());
        } catch (OptimisticLockingFailureException e) {
            log.warn("Optimistic lock conflict for task: {}", task.getTaskId());
            throw BusinessException.conflict(task.getTaskId());
        }
    }

    public void delete(String taskId) {
        ScheduledTask task = findById(taskId);
        taskMapper.deleteById(task.getId());
        cacheManager.invalidate(CACHE_NAME, taskId);
        log.info("Deleted task: {}", taskId);
    }

    public IPage<ScheduledTask> findAll(int page, int size) {
        return taskMapper.selectPage(new Page<>(page, size), null);
    }

    public IPage<ScheduledTask> findByNamespace(String namespace, int page, int size) {
        return taskMapper.findByNamespace(new Page<>(page, size), namespace);
    }

    public List<ScheduledTask> findTasksToExecute() {
        return taskMapper.findTasksToExecute(Instant.now());
    }

    public List<ScheduledTask> findByTaskType(String taskType) {
        return taskMapper.findByTaskType(taskType);
    }
}
