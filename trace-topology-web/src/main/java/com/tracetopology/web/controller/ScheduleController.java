package com.tracetopology.web.controller;

import com.tracetopology.api.service.SchedulingService;
import com.tracetopology.common.result.Result;
import com.tracetopology.domain.schedule.TaskExecution;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/schedule")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService schedulingService;

    @PostMapping("/tasks")
    public Mono<Result<TaskExecution>> executeTask(@RequestBody TaskRequest request) {
        return Mono.fromCallable(() -> {
            TaskExecution execution = schedulingService.executeTask(
                    request.getTaskType(),
                    request.getParams(),
                    request.getTimeoutSeconds()
            );
            return Result.success(execution);
        });
    }

    @PostMapping("/tasks/{taskId}/schedule")
    public Mono<Result<TaskExecution>> scheduleTask(
            @PathVariable String taskId,
            @RequestBody ScheduleRequest request) {
        return Mono.fromCallable(() -> {
            TaskExecution execution = schedulingService.scheduleTask(
                    taskId,
                    request.getTaskType(),
                    request.getCronExpression(),
                    request.getParams()
            );
            return Result.success(execution);
        });
    }

    @GetMapping("/tasks/{executionId}")
    public Mono<Result<TaskExecution>> getTaskExecution(@PathVariable String executionId) {
        return Mono.fromCallable(() -> {
            TaskExecution execution = schedulingService.getTaskExecution(executionId);
            return Result.success(execution);
        });
    }

    @GetMapping("/tasks")
    public Mono<Result<List<TaskExecution>>> listTaskExecutions(
            @RequestParam(required = false) String taskType,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Mono.fromCallable(() -> {
            List<TaskExecution> executions = schedulingService.listTaskExecutions(taskType, pageNum, pageSize);
            return Result.success(executions);
        });
    }

    @PostMapping("/tasks/{executionId}/cancel")
    public Mono<Result<Void>> cancelTask(@PathVariable String executionId) {
        return Mono.fromCallable(() -> {
            schedulingService.cancelTask(executionId);
            return Result.success();
        });
    }

    @GetMapping("/tasks/running")
    public Mono<Result<List<TaskExecution>>> getRunningTasks() {
        return Mono.fromCallable(() -> {
            List<TaskExecution> running = schedulingService.getRunningTasks();
            return Result.success(running);
        });
    }

    @PostMapping("/tasks/recover")
    public Mono<Result<Map<String, Object>>> recoverFailedTasks() {
        return Mono.fromCallable(() -> {
            int recovered = schedulingService.recoverFailedTasks();
            Map<String, Object> result = Map.of(
                    "recoveredCount", recovered,
                    "timestamp", java.time.Instant.now().toString()
            );
            return Result.success(result);
        });
    }

    @Data
    public static class TaskRequest {
        private String taskType;
        private Map<String, Object> params;
        private int timeoutSeconds = 300;
    }

    @Data
    public static class ScheduleRequest {
        private String taskType;
        private String cronExpression;
        private Map<String, Object> params;
    }
}
