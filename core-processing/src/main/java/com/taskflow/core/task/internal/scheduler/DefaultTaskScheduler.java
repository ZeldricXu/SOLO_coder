package com.taskflow.core.task.internal.scheduler;

import com.taskflow.common.utils.IdGenerator;
import com.taskflow.core.task.api.TaskScheduler;
import com.taskflow.core.task.domain.Task;
import com.taskflow.data.entity.TaskEntity;
import com.taskflow.data.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 默认任务调度器实现
 * 内部实现，不对外暴露
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultTaskScheduler implements TaskScheduler {

    private final TaskService taskService;

    @Override
    public Mono<Task> schedule(Task task) {
        return Mono.fromCallable(() -> {
            TaskEntity entity = new TaskEntity();
            entity.setTenantId(task.getTenantId());
            entity.setTaskId(task.getTaskId() != null ? task.getTaskId() : IdGenerator.generateId("task"));
            entity.setName(task.getName());
            entity.setDescription(task.getDescription());
            entity.setType(task.getType());
            entity.setStatus(task.getStatus() != null ? task.getStatus() : "active");
            entity.setCronExpression(task.getCronExpression());
            entity.setParametersMap(task.getParameters());
            entity.setHandlerClass(task.getHandlerType());
            entity.setTimeoutSeconds(task.getTimeoutSeconds());
            entity.setMaxRetry(task.getMaxRetry());
            entity.setFlowId(task.getFlowId());

            if (task.getCronExpression() != null) {
                CronExpression cronExpression.parse(task.getCronExpression());
                entity.setNextRunTime(cronExpression.next(LocalDateTime.now()));
            }

            TaskEntity saved = taskService.create(entity);
            return toDomain(saved);
        });
    }

    @Override
    public Mono<Boolean> unschedule(String tenantId, String taskId) {
        return Mono.fromCallable(() -> {
            taskService.updateStatus(taskId, "inactive");
            return true;
        });
    }

    @Override
    public Mono<List<Task>> getTasksToRun(String tenantId, LocalDateTime time) {
        return Mono.fromCallable(() -> {
            List<TaskEntity> entities = taskService.getTasksToRun(tenantId, time);
            return entities.stream()
                    .map(this::toDomain)
                    .collect(Collectors.toList());
        });
    }

    @Override
    public void updateNextRunTime(String taskId, LocalDateTime lastRunTime, LocalDateTime nextRunTime) {
        taskService.updateRunTimes(taskId, lastRunTime, nextRunTime);
    }

    private Task toDomain(TaskEntity entity) {
        return Task.builder()
                .taskId(entity.getTaskId())
                .tenantId(entity.getTenantId())
                .name(entity.getName())
                .description(entity.getDescription())
                .type(entity.getType())
                .status(entity.getStatus())
                .cronExpression(entity.getCronExpression())
                .nextRunTime(entity.getNextRunTime())
                .lastRunTime(entity.getLastRunTime())
                .parameters(entity.getParametersMap())
                .handlerType(entity.getHandlerClass())
                .timeoutSeconds(entity.getTimeoutSeconds())
                .maxRetry(entity.getMaxRetry())
                .flowId(entity.getFlowId())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
