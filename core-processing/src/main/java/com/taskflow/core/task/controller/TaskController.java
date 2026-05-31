package com.taskflow.core.task.controller;

import com.taskflow.common.model.Result;
import com.taskflow.core.task.api.TaskExecutor;
import com.taskflow.core.task.api.TaskRegistry;
import com.taskflow.core.task.api.TaskScheduler;
import com.taskflow.core.task.domain.Task;
import com.taskflow.core.task.domain.TaskRequest;
import com.taskflow.core.task.domain.TaskResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

/**
 * 任务控制器
 * 仅依赖TaskExecutor、TaskScheduler、TaskRegistry接口，实现依赖倒置
 */
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskExecutor taskExecutor;
    private final TaskScheduler taskScheduler;
    private final TaskRegistry taskRegistry;

    @PostMapping("/execute")
    public Mono<Result<TaskResult>> executeTask(@RequestBody TaskRequest request) {
        return taskExecutor.execute(request)
                .map(Result::success);
    }

    @GetMapping("/{runId}/status")
    public Mono<Result<TaskResult>> getTaskStatus(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String runId) {
        return taskExecutor.getStatus(tenantId, runId)
                .map(Result::success);
    }

    @PostMapping("/{runId}/cancel")
    public Mono<Result<Boolean>> cancelTask(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String runId) {
        return taskExecutor.cancel(tenantId, runId)
                .map(Result::success);
    }

    @PostMapping
    public Mono<Result<Task>> createTask(@RequestBody Task task) {
        return taskScheduler.schedule(task)
                .map(Result::success);
    }

    @GetMapping("/handlers")
    public Result<Set<String>> getHandlerTypes() {
        return Result.success(taskRegistry.getHandlerTypes());
    }

    @PostMapping("/batch")
    public Mono<Result<Map<String, Object>>> batchExecute(@RequestBody Map<String, Object> request) {
        return Mono.just(Result.success(Map.of(
                "batchId", "batch_" + System.currentTimeMillis(),
                "message", "Batch operation submitted"
        )));
    }
}
