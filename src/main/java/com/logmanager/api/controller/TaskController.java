package com.logmanager.api.controller;

import com.logmanager.api.dto.TaskDTO;
import com.logmanager.api.vo.ApiResponse;
import com.logmanager.common.enums.TaskStatus;
import com.logmanager.domain.model.Task;
import com.logmanager.service.TaskSchedulerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskSchedulerService taskSchedulerService;

    @PostMapping
    public Mono<ApiResponse<Task>> scheduleTask(@Valid @RequestBody TaskDTO dto) {
        Instant scheduledAt = dto.getScheduledAt() != null ? dto.getScheduledAt() : Instant.now();
        return taskSchedulerService.scheduleTask(
                dto.getName(),
                dto.getType(),
                dto.getParameters(),
                dto.getScheduledBy(),
                scheduledAt
        ).map(ApiResponse::created);
    }

    @PostMapping("/{id}/execute")
    public Mono<ApiResponse<Task>> executeTask(@PathVariable String id) {
        return taskSchedulerService.executeTask(id)
                .map(ApiResponse::success)
                .onErrorResume(e -> Mono.just(ApiResponse.error(400, e.getMessage())));
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<Task>> getTask(@PathVariable String id) {
        return taskSchedulerService.getTask(id)
                .map(ApiResponse::success)
                .defaultIfEmpty(ApiResponse.error(404, "Task not found"));
    }

    @GetMapping("/status/{status}")
    public Mono<ApiResponse<Flux<Task>>> getTasksByStatus(@PathVariable String status) {
        TaskStatus taskStatus = TaskStatus.valueOf(status.toUpperCase());
        return Mono.just(ApiResponse.success(taskSchedulerService.getTasksByStatus(taskStatus)));
    }

    @GetMapping("/type/{type}")
    public Mono<ApiResponse<Flux<Task>>> getTasksByType(@PathVariable String type) {
        return Mono.just(ApiResponse.success(taskSchedulerService.getTasksByType(type)));
    }

    @PutMapping("/{id}/progress")
    public Mono<ApiResponse<Task>> updateTaskProgress(@PathVariable String id, @RequestParam double progress) {
        return taskSchedulerService.updateTaskProgress(id, progress)
                .map(ApiResponse::success);
    }

    @PostMapping("/{id}/complete")
    public Mono<ApiResponse<Task>> completeTask(@PathVariable String id, @RequestBody String result) {
        return taskSchedulerService.completeTask(id, result)
                .map(ApiResponse::success);
    }

    @PostMapping("/{id}/fail")
    public Mono<ApiResponse<Task>> failTask(@PathVariable String id, @RequestBody String errorMessage) {
        return taskSchedulerService.failTask(id, errorMessage)
                .map(ApiResponse::success);
    }

    @PostMapping("/{id}/cancel")
    public Mono<ApiResponse<Task>> cancelTask(@PathVariable String id) {
        return taskSchedulerService.cancelTask(id)
                .map(ApiResponse::success);
    }

    @GetMapping("/count/{status}")
    public Mono<ApiResponse<Long>> getTaskCountByStatus(@PathVariable String status) {
        TaskStatus taskStatus = TaskStatus.valueOf(status.toUpperCase());
        return taskSchedulerService.getTaskCountByStatus(taskStatus)
                .map(ApiResponse::success);
    }
}
