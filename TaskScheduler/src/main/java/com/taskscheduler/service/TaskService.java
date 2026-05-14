package com.taskscheduler.service;

import com.taskscheduler.dto.CreateTaskRequest;
import com.taskscheduler.dto.UpdateTaskRequest;
import com.taskscheduler.entity.Dependency;
import com.taskscheduler.entity.TaskConfig;
import com.taskscheduler.exception.TaskNotFoundException;
import com.taskscheduler.repository.DependencyRepository;
import com.taskscheduler.repository.TaskConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskConfigRepository taskConfigRepository;
    private final DependencyRepository dependencyRepository;
    private final SchedulerService schedulerService;
    private final TaskTimeoutService taskTimeoutService;

    @Transactional
    public TaskConfig createTask(CreateTaskRequest request) {
        String taskId = StringUtils.isNotBlank(request.getTaskId())
                ? request.getTaskId()
                : "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        if (taskConfigRepository.existsByTaskId(taskId)) {
            throw new IllegalArgumentException("Task already exists: " + taskId);
        }

        String taskType = StringUtils.isNotBlank(request.getTaskType()) ? request.getTaskType() : "batch";
        
        TaskConfig taskConfig = new TaskConfig();
        taskConfig.setTaskId(taskId);
        taskConfig.setTaskName(request.getTaskName());
        taskConfig.setTaskType(taskType);
        taskConfig.setExecuteCommand(request.getExecuteCommand());
        
        if (request.getScheduleConfig() != null) {
            taskConfig.setCronExpression(request.getScheduleConfig().getCron());
            taskConfig.setTimezone(request.getScheduleConfig().getTimezone());
        }
        
        taskConfig.setRetryCount(request.getRetryCount() != null ? request.getRetryCount() : 0);
        
        if (request.getTimeout() != null && request.getTimeout() > 0) {
            taskConfig.setTimeoutSeconds(request.getTimeout());
            log.info("Using explicit timeout {}s for task: {}", request.getTimeout(), taskId);
        } else {
            int typeBasedTimeout = taskTimeoutService.getTimeoutForTaskType(taskType);
            taskConfig.setTimeoutSeconds(typeBasedTimeout);
            log.info("Using task type based timeout {}s for task: {}, type: {}", typeBasedTimeout, taskId, taskType);
        }
        
        taskConfig.setPriority(request.getPriority() != null ? request.getPriority() : 1);
        taskConfig.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        taskConfig.setMaxConcurrent(request.getMaxConcurrent() != null ? request.getMaxConcurrent() : 1);
        taskConfig.setDependencies(request.getDependencies() != null ? request.getDependencies() : List.of());

        TaskConfig savedTask = taskConfigRepository.save(taskConfig);

        if (request.getDependencies() != null && !request.getDependencies().isEmpty()) {
            for (String dependsOn : request.getDependencies()) {
                Dependency dependency = new Dependency();
                dependency.setTaskId(taskId);
                dependency.setDependsOn(dependsOn);
                dependency.setDependencyType("sequential");
                dependencyRepository.save(dependency);
            }
        }

        if (StringUtils.isNotBlank(taskConfig.getCronExpression()) && taskConfig.getEnabled()) {
            schedulerService.scheduleTask(savedTask);
        }

        log.info("Task created: {} with timeout: {}s, type: {}", 
                taskId, taskConfig.getTimeoutSeconds(), taskType);
        return savedTask;
    }

    @Transactional
    public TaskConfig updateTask(String taskId, UpdateTaskRequest request) {
        TaskConfig taskConfig = taskConfigRepository.findByTaskId(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId, "Task not found"));

        boolean needReschedule = false;
        String oldCron = taskConfig.getCronExpression();
        boolean oldEnabled = taskConfig.getEnabled();

        if (StringUtils.isNotBlank(request.getTaskName())) {
            taskConfig.setTaskName(request.getTaskName());
        }
        if (StringUtils.isNotBlank(request.getTaskType())) {
            taskConfig.setTaskType(request.getTaskType());
            if (request.getTimeout() == null) {
                int newTimeout = taskTimeoutService.getTimeoutForTaskType(request.getTaskType());
                taskConfig.setTimeoutSeconds(newTimeout);
                log.info("Updated task type and timeout for task: {} to {}s", taskId, newTimeout);
            }
        }
        if (StringUtils.isNotBlank(request.getExecuteCommand())) {
            taskConfig.setExecuteCommand(request.getExecuteCommand());
        }
        if (request.getScheduleConfig() != null) {
            taskConfig.setCronExpression(request.getScheduleConfig().getCron());
            taskConfig.setTimezone(request.getScheduleConfig().getTimezone());
            needReschedule = true;
        }
        if (request.getRetryCount() != null) {
            taskConfig.setRetryCount(request.getRetryCount());
        }
        if (request.getTimeout() != null) {
            taskConfig.setTimeoutSeconds(request.getTimeout());
            log.info("Updated explicit timeout for task: {} to {}s", taskId, request.getTimeout());
        }
        if (request.getPriority() != null) {
            taskConfig.setPriority(request.getPriority());
        }
        if (request.getEnabled() != null) {
            taskConfig.setEnabled(request.getEnabled());
            needReschedule = true;
        }
        if (request.getMaxConcurrent() != null) {
            taskConfig.setMaxConcurrent(request.getMaxConcurrent());
        }
        if (request.getDependencies() != null) {
            taskConfig.setDependencies(request.getDependencies());
            dependencyRepository.deleteByTaskId(taskId);
            for (String dependsOn : request.getDependencies()) {
                Dependency dependency = new Dependency();
                dependency.setTaskId(taskId);
                dependency.setDependsOn(dependsOn);
                dependency.setDependencyType("sequential");
                dependencyRepository.save(dependency);
            }
        }

        TaskConfig updatedTask = taskConfigRepository.save(taskConfig);

        if (needReschedule) {
            if (StringUtils.isNotBlank(oldCron)) {
                schedulerService.unscheduleTask(taskId);
            }
            if (StringUtils.isNotBlank(updatedTask.getCronExpression()) && updatedTask.getEnabled()) {
                schedulerService.scheduleTask(updatedTask);
            }
        }

        log.info("Task updated: {}", taskId);
        return updatedTask;
    }

    @Transactional
    public void deleteTask(String taskId) {
        if (!taskConfigRepository.existsByTaskId(taskId)) {
            throw new TaskNotFoundException(taskId, "Task not found");
        }

        schedulerService.unscheduleTask(taskId);
        dependencyRepository.deleteByTaskId(taskId);
        taskConfigRepository.deleteById(taskId);

        log.info("Task deleted: {}", taskId);
    }

    public Optional<TaskConfig> getTask(String taskId) {
        return taskConfigRepository.findByTaskId(taskId);
    }

    public List<TaskConfig> getAllTasks() {
        return taskConfigRepository.findAll();
    }

    public List<TaskConfig> getEnabledTasks() {
        return taskConfigRepository.findByEnabledTrue();
    }

    @Transactional
    public boolean enableTask(String taskId) {
        TaskConfig taskConfig = taskConfigRepository.findByTaskId(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId, "Task not found"));

        taskConfig.setEnabled(true);
        taskConfigRepository.save(taskConfig);

        if (StringUtils.isNotBlank(taskConfig.getCronExpression())) {
            schedulerService.scheduleTask(taskConfig);
        }

        log.info("Task enabled: {}", taskId);
        return true;
    }

    @Transactional
    public boolean disableTask(String taskId) {
        TaskConfig taskConfig = taskConfigRepository.findByTaskId(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId, "Task not found"));

        taskConfig.setEnabled(false);
        taskConfigRepository.save(taskConfig);

        schedulerService.unscheduleTask(taskId);

        log.info("Task disabled: {}", taskId);
        return true;
    }

    public int getTaskEffectiveTimeout(String taskId) {
        return taskTimeoutService.getTimeoutForTask(taskId);
    }

    public boolean isLightweightTask(String taskId) {
        Optional<TaskConfig> taskOpt = taskConfigRepository.findByTaskId(taskId);
        return taskOpt.map(task -> taskTimeoutService.isLightweightTask(task.getTaskType()))
                .orElse(false);
    }

    public boolean isHeavyTask(String taskId) {
        Optional<TaskConfig> taskOpt = taskConfigRepository.findByTaskId(taskId);
        return taskOpt.map(task -> taskTimeoutService.isHeavyTask(task.getTaskType()))
                .orElse(false);
    }
}
