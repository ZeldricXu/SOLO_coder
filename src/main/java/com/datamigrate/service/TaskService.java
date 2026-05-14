package com.datamigrate.service;

import com.datamigrate.common.TaskStatus;
import com.datamigrate.dto.*;
import com.datamigrate.entity.MappingRule;
import com.datamigrate.entity.MigrateTask;
import com.datamigrate.repository.MappingRuleRepository;
import com.datamigrate.repository.MigrateTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final MigrateTaskRepository taskRepository;
    private final MappingRuleRepository mappingRuleRepository;
    private final LogService logService;

    @Transactional
    public CreateTaskResponse createTask(CreateTaskRequest request) {
        String taskId = "task_migrate_" + UUID.randomUUID().toString().substring(0, 8);

        MigrateTask task = new MigrateTask();
        task.setTaskId(taskId);
        task.setTaskName(request.getTaskName());
        task.setDescription(request.getDescription());
        task.setStatus(TaskStatus.PENDING);

        if (request.getSourceConfig() != null) {
            task.setSourceType(request.getSourceConfig().getSourceType());
            task.setSourceHost(request.getSourceConfig().getHost());
            task.setSourcePort(request.getSourceConfig().getPort());
            task.setSourceDatabase(request.getSourceConfig().getDatabase());
            task.setSourceUsername(request.getSourceConfig().getUsername());
            task.setSourcePassword(request.getSourceConfig().getPassword());
            task.setSourceTable(request.getSourceConfig().getTable());
            task.setSourceQuery(request.getSourceConfig().getQuery());
        }

        if (request.getTargetConfig() != null) {
            task.setTargetType(request.getTargetConfig().getTargetType());
            task.setTargetHost(request.getTargetConfig().getHost());
            task.setTargetPort(request.getTargetConfig().getPort());
            task.setTargetDatabase(request.getTargetConfig().getDatabase());
            task.setTargetUsername(request.getTargetConfig().getUsername());
            task.setTargetPassword(request.getTargetConfig().getPassword());
            task.setTargetTable(request.getTargetConfig().getTable());
        }

        if (request.getBatchSize() != null) {
            task.setBatchSize(request.getBatchSize());
        }
        if (request.getMaxRetryTimes() != null) {
            task.setMaxRetryTimes(request.getMaxRetryTimes());
        }
        if (request.getAutoVerify() != null) {
            task.setAutoVerify(request.getAutoVerify());
        }
        task.setPrimaryKeyField(request.getPrimaryKeyField());

        task = taskRepository.save(task);

        if (request.getMappingRules() != null && !request.getMappingRules().isEmpty()) {
            int order = 0;
            for (CreateTaskRequest.MappingRuleDto ruleDto : request.getMappingRules()) {
                MappingRule rule = new MappingRule();
                rule.setTask(task);
                rule.setSourceField(ruleDto.getSourceField());
                rule.setTargetField(ruleDto.getTargetField());
                rule.setTransformation(ruleDto.getTransformation());
                rule.setRuleOrder(ruleDto.getRuleOrder() != null ? ruleDto.getRuleOrder() : order++);
                mappingRuleRepository.save(rule);
            }
        }

        logService.logSystem(taskId, "迁移任务创建成功: " + request.getTaskName());

        return new CreateTaskResponse(taskId);
    }

    public Optional<TaskDetailResponse> getTaskDetail(String taskId) {
        return taskRepository.findByTaskId(taskId).map(this::convertToDetailResponse);
    }

    public TaskListResponse listTasks(String status, String keyword, int page, int size) {
        List<MigrateTask> tasks;
        
        if (status != null && !status.isEmpty()) {
            tasks = taskRepository.findByStatus(TaskStatus.valueOf(status.toUpperCase()));
        } else if (keyword != null && !keyword.isEmpty()) {
            tasks = taskRepository.findByTaskNameContaining(keyword);
        } else {
            tasks = taskRepository.findAll();
        }

        List<TaskListResponse.TaskInfo> taskInfos = tasks.stream()
            .map(this::convertToListInfo)
            .collect(Collectors.toList());

        return new TaskListResponse(taskInfos, tasks.size());
    }

    @Transactional
    public boolean updateTaskStatus(String taskId, TaskStatus status) {
        Optional<MigrateTask> taskOpt = taskRepository.findByTaskId(taskId);
        if (taskOpt.isPresent()) {
            MigrateTask task = taskOpt.get();
            task.setStatus(status);
            if (status == TaskStatus.RUNNING && task.getStartedAt() == null) {
                task.setStartedAt(LocalDateTime.now());
            }
            if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED) {
                task.setCompletedAt(LocalDateTime.now());
            }
            taskRepository.save(task);
            return true;
        }
        return false;
    }

    @Transactional
    public boolean deleteTask(String taskId) {
        if (taskRepository.findByTaskId(taskId).isPresent()) {
            mappingRuleRepository.deleteByTask_TaskId(taskId);
            taskRepository.deleteById(taskId);
            logService.logSystem(taskId, "迁移任务已删除");
            return true;
        }
        return false;
    }

    @Transactional
    public boolean pauseTask(String taskId) {
        return updateTaskStatus(taskId, TaskStatus.PAUSED);
    }

    @Transactional
    public boolean resumeTask(String taskId) {
        return updateTaskStatus(taskId, TaskStatus.RUNNING);
    }

    @Transactional
    public boolean cancelTask(String taskId) {
        return updateTaskStatus(taskId, TaskStatus.CANCELLED);
    }

    public List<MigrateTask> getPendingTasks() {
        return taskRepository.findPendingOrPausedTasks();
    }

    public Optional<MigrateTask> getTask(String taskId) {
        return taskRepository.findByTaskId(taskId);
    }

    public List<MappingRule> getMappingRules(String taskId) {
        return mappingRuleRepository.findByTask_TaskIdOrderByRuleOrderAsc(taskId);
    }

    private TaskDetailResponse convertToDetailResponse(MigrateTask task) {
        TaskDetailResponse response = new TaskDetailResponse();
        response.setTaskId(task.getTaskId());
        response.setTaskName(task.getTaskName());
        response.setSourceType(task.getSourceType());
        response.setSourceHost(task.getSourceHost());
        response.setSourcePort(task.getSourcePort());
        response.setSourceDatabase(task.getSourceDatabase());
        response.setSourceTable(task.getSourceTable());
        response.setSourceQuery(task.getSourceQuery());
        response.setTargetType(task.getTargetType());
        response.setTargetHost(task.getTargetHost());
        response.setTargetPort(task.getTargetPort());
        response.setTargetDatabase(task.getTargetDatabase());
        response.setTargetTable(task.getTargetTable());
        response.setPrimaryKeyField(task.getPrimaryKeyField());
        response.setBatchSize(task.getBatchSize());
        response.setMaxRetryTimes(task.getMaxRetryTimes());
        response.setAutoVerify(task.getAutoVerify());
        response.setStatus(task.getStatus());
        response.setDescription(task.getDescription());
        response.setCreatedAt(task.getCreatedAt());
        response.setUpdatedAt(task.getUpdatedAt());
        response.setStartedAt(task.getStartedAt());
        response.setCompletedAt(task.getCompletedAt());
        response.setMappingRules(getMappingRules(task.getTaskId()));
        return response;
    }

    private TaskListResponse.TaskInfo convertToListInfo(MigrateTask task) {
        TaskListResponse.TaskInfo info = new TaskListResponse.TaskInfo();
        info.setTaskId(task.getTaskId());
        info.setTaskName(task.getTaskName());
        info.setStatus(task.getStatus());
        info.setCreatedAt(task.getCreatedAt());
        info.setStartedAt(task.getStartedAt());
        info.setCompletedAt(task.getCompletedAt());
        return info;
    }
}
