package com.metricplatform.service;

import com.metricplatform.dto.TaskExecutionResult;
import com.metricplatform.entity.SysScheduledTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Component
public class DefaultTaskHandler implements TaskHandler {

    @Override
    public String getTaskType() {
        return "DEFAULT";
    }

    @Override
    public TaskExecutionResult execute(SysScheduledTask task, Map<String, Object> context) {
        LocalDateTime startTime = LocalDateTime.now();
        log.info("执行默认任务: {} (ID: {})", task.getTaskName(), task.getTaskId());

        try {
            Thread.sleep(100);

            return TaskExecutionResult.builder()
                    .taskId(task.getTaskId())
                    .taskName(task.getTaskName())
                    .status("completed")
                    .result("任务执行成功")
                    .startTime(startTime)
                    .endTime(LocalDateTime.now())
                    .durationMs(java.time.Duration.between(startTime, LocalDateTime.now()).toMillis())
                    .build();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TaskExecutionResult.builder()
                    .taskId(task.getTaskId())
                    .taskName(task.getTaskName())
                    .status("failed")
                    .errorMessage("任务被中断: " + e.getMessage())
                    .startTime(startTime)
                    .endTime(LocalDateTime.now())
                    .durationMs(java.time.Duration.between(startTime, LocalDateTime.now()).toMillis())
                    .build();
        }
    }
}
