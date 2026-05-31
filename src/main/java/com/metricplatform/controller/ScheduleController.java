package com.metricplatform.controller;

import com.metricplatform.common.ApiResponse;
import com.metricplatform.dto.ScheduledTaskDTO;
import com.metricplatform.dto.TaskExecutionResult;
import com.metricplatform.entity.SysScheduledTask;
import com.metricplatform.service.ScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    @GetMapping
    public Mono<ApiResponse<List<SysScheduledTask>>> getAllTasks() {
        return Mono.just(ApiResponse.success(scheduleService.getAllTasks()));
    }

    @GetMapping("/{taskId}")
    public Mono<ApiResponse<SysScheduledTask>> getTask(@PathVariable String taskId) {
        SysScheduledTask task = scheduleService.getById(taskId);
        if (task != null) {
            return Mono.just(ApiResponse.success(task));
        } else {
            return Mono.just(ApiResponse.notFound("任务不存在"));
        }
    }

    @GetMapping("/handlers")
    public Mono<ApiResponse<Set<String>>> getRegisteredHandlers() {
        return Mono.just(ApiResponse.success(scheduleService.getRegisteredHandlers().keySet()));
    }

    @PostMapping
    public Mono<ApiResponse<SysScheduledTask>> createTask(@Valid @RequestBody ScheduledTaskDTO dto) {
        try {
            SysScheduledTask task = scheduleService.createTask(dto);
            return Mono.just(ApiResponse.created(task));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Mono.just(ApiResponse.validationError(e.getMessage()));
        }
    }

    @PostMapping("/{taskId}/start")
    public Mono<ApiResponse<SysScheduledTask>> startTask(@PathVariable String taskId) {
        try {
            SysScheduledTask task = scheduleService.startTask(taskId);
            return Mono.just(ApiResponse.success(task));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.notFound(e.getMessage()));
        } catch (IllegalStateException e) {
            return Mono.just(ApiResponse.validationError(e.getMessage()));
        }
    }

    @PostMapping("/{taskId}/pause")
    public Mono<ApiResponse<SysScheduledTask>> pauseTask(@PathVariable String taskId) {
        try {
            SysScheduledTask task = scheduleService.pauseTask(taskId);
            return Mono.just(ApiResponse.success(task));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.notFound(e.getMessage()));
        }
    }

    @PostMapping("/{taskId}/stop")
    public Mono<ApiResponse<SysScheduledTask>> stopTask(@PathVariable String taskId) {
        try {
            SysScheduledTask task = scheduleService.stopTask(taskId);
            return Mono.just(ApiResponse.success(task));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.notFound(e.getMessage()));
        }
    }

    @PostMapping("/{taskId}/execute")
    public Mono<ApiResponse<Map<String, Object>>> executeTask(@PathVariable String taskId) {
        SysScheduledTask task = scheduleService.getById(taskId);
        if (task == null) {
            return Mono.just(ApiResponse.notFound("任务不存在"));
        }

        scheduleService.executeTaskWithDependencies(taskId);
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("taskName", task.getTaskName());
        result.put("message", "任务已异步提交执行");
        return Mono.just(ApiResponse.success(result));
    }

    @GetMapping("/{taskId}/result")
    public Mono<ApiResponse<TaskExecutionResult>> getTaskResult(@PathVariable String taskId) {
        TaskExecutionResult result = scheduleService.getTaskResult(taskId);
        if (result != null) {
            return Mono.just(ApiResponse.success(result));
        } else {
            return Mono.just(ApiResponse.notFound("任务执行结果不存在或尚未执行"));
        }
    }

    @DeleteMapping("/{taskId}")
    public Mono<ApiResponse<Void>> deleteTask(@PathVariable String taskId) {
        try {
            boolean result = scheduleService.deleteTask(taskId);
            if (result) {
                return Mono.just(ApiResponse.success(null));
            } else {
                return Mono.just(ApiResponse.notFound("任务不存在"));
            }
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.notFound(e.getMessage()));
        }
    }
}
