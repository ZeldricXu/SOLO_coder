package com.taskflow.core.task.internal.scheduler;

import com.taskflow.core.task.api.TaskExecutor;
import com.taskflow.core.task.api.TaskScheduler;
import com.taskflow.core.task.domain.Task;
import com.taskflow.core.task.domain.TaskRequest;
import com.taskflow.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务调度触发器
 * 内部实现，不对外暴露
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskScheduleTrigger {

    private final TaskScheduler taskScheduler;
    private final TaskExecutor taskExecutor;
    private final TenantService tenantService;

    @Scheduled(cron = "*/30 * * * * *")
    public void scanAndExecuteTasks() {
        log.debug("Scanning for scheduled tasks...");

        List<String> tenantIds = List.of("default");

        for (String tenantId : tenantIds) {
            if (!tenantService.isTenantActive(tenantId)) {
                continue;
            }

            LocalDateTime now = LocalDateTime.now();
            taskScheduler.getTasksToRun(tenantId, now)
                    .subscribe(
                            tasks -> {
                                for (Task task : tasks) {
                                    executeScheduledTask(tenantId, task, now);
                                }
                            },
                            error -> log.error("Failed to get tasks to run", error)
                    );
        }
    }

    private void executeScheduledTask(String tenantId, Task task, LocalDateTime now) {
        log.info("Executing scheduled task: {} ({})", task.getName(), task.getTaskId());

        TaskRequest request = TaskRequest.builder()
                .taskId(task.getTaskId())
                .tenantId(tenantId)
                .triggerType("scheduled")
                .build();

        taskExecutor.execute(request)
                .subscribe(
                        result -> {
                            log.info("Scheduled task completed: {}, status: {}", task.getTaskId(), result.getStatus());
                            updateNextRunTime(task, now);
                        },
                        error -> {
                            log.error("Scheduled task failed: {}", task.getTaskId(), error);
                            updateNextRunTime(task, now);
                        }
                );
    }

    private void updateNextRunTime(Task task, LocalDateTime now) {
        try {
            if (task.getCronExpression() != null && !task.getCronExpression().isEmpty()) {
                CronExpression cron = CronExpression.parse(task.getCronExpression());
                LocalDateTime nextRun = cron.next(now);
                taskScheduler.updateNextRunTime(task.getTaskId(), now, nextRun);
                log.info("Updated next run time for task {}: {}", task.getTaskId(), nextRun);
            }
        } catch (Exception e) {
            log.error("Failed to update next run time for task: {}", task.getTaskId(), e);
        }
    }
}
