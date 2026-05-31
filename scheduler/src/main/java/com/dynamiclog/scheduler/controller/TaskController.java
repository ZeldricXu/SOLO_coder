package com.dynamiclog.scheduler.controller;

import com.dynamiclog.common.dto.ApiResponse;
import com.dynamiclog.common.entity.Task;
import com.dynamiclog.common.entity.TaskRun;
import com.dynamiclog.common.enums.TaskStatus;
import com.dynamiclog.scheduler.service.TaskSchedulerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskSchedulerService taskSchedulerService;

    @PostMapping
    public Mono<ApiResponse<Task>> createTask(@RequestBody Task task) {
        return taskSchedulerService.createTask(task)
                .map(ApiResponse::success);
    }

    @PostMapping("/{taskId}/schedule")
    public Mono<ApiResponse<Task>> scheduleTask(@PathVariable String taskId) {
        return taskSchedulerService.scheduleTask(taskId)
                .map(ApiResponse::success);
    }

    @PostMapping("/{taskId}/execute")
    public Mono<ApiResponse<Void>> executeTaskNow(@PathVariable String taskId) {
        return taskSchedulerService.executeTaskNow(taskId)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @GetMapping("/{taskId}")
    public Mono<ApiResponse<Task>> getTask(@PathVariable String taskId) {
        return taskSchedulerService.getTask(taskId)
                .map(ApiResponse::success);
    }

    @GetMapping
    public Mono<ApiResponse<List<Task>>> getTasksByStatus(@RequestParam(required = false) TaskStatus status) {
        Flux<Task> tasks = status != null ?
                taskSchedulerService.getTasksByStatus(status) :
                taskSchedulerService.getTasksByStatus(null);
        return tasks.collectList().map(ApiResponse::success);
    }

    @GetMapping("/{taskId}/dependencies")
    public Mono<ApiResponse<List<Task>>> getTaskDependencies(@PathVariable String taskId) {
        return taskSchedulerService.getTaskDependencies(taskId)
                .map(ApiResponse::success);
    }

    @GetMapping("/{taskId}/runs")
    public Mono<ApiResponse<List<TaskRun>>> getTaskRuns(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "10") int limit) {
        return taskSchedulerService.getTaskRuns(taskId, limit)
                .collectList()
                .map(ApiResponse::success);
    }

    @DeleteMapping("/{taskId}")
    public Mono<ApiResponse<Void>> cancelTask(@PathVariable String taskId) {
        return taskSchedulerService.cancelTask(taskId)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @GetMapping("/runs/{runId}")
    public Mono<ApiResponse<TaskRun>> getTaskRun(@PathVariable String runId) {
        return taskSchedulerService.getTaskRun(runId)
                .map(ApiResponse::success);
    }

    @PostMapping("/resources/acquire")
    public Mono<ApiResponse<TaskSchedulerService.ResourceLease>> acquireResource(
            @RequestParam String resourceType,
            @RequestParam(defaultValue = "5000") long timeoutMs) {
        return taskSchedulerService.acquireResource(resourceType, timeoutMs)
                .map(ApiResponse::success)
                .onErrorResume(e -> Mono.just(ApiResponse.error(503, e.getMessage())));
    }

    @PostMapping("/resources/release")
    public Mono<ApiResponse<Void>> releaseResource(@RequestBody TaskSchedulerService.ResourceLease lease) {
        return taskSchedulerService.releaseResource(lease)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @GetMapping("/pool/stats")
    public Mono<ApiResponse<Map<String, Object>>> getPoolStats() {
        return taskSchedulerService.getPoolStats()
                .map(ApiResponse::success);
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<TaskSchedulerService.TaskEvent> listenEvents() {
        return taskSchedulerService.listenEvents();
    }
}
