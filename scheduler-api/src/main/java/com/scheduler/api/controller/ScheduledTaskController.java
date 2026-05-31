package com.scheduler.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.scheduler.common.model.ApiResponse;
import com.scheduler.data.repository.ScheduledTaskRepository;
import com.scheduler.persistence.entity.ScheduledTask;
import com.scheduler.scheduler.service.ScheduleManagerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class ScheduledTaskController {

    private final ScheduleManagerService scheduleManagerService;
    private final ScheduledTaskRepository taskRepository;

    @PostMapping
    public Mono<ResponseEntity<ApiResponse<ScheduledTask>>> createTask(@RequestBody ScheduledTask task) {
        return Mono.fromCallable(() -> {
            ScheduledTask created = scheduleManagerService.createTask(task);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.created(created));
        });
    }

    @GetMapping("/{taskId}")
    public Mono<ResponseEntity<ApiResponse<ScheduledTask>>> getTask(@PathVariable String taskId) {
        return Mono.fromCallable(() -> {
            ScheduledTask task = taskRepository.findById(taskId);
            return ResponseEntity.ok(ApiResponse.success(task));
        });
    }

    @PutMapping("/{taskId}")
    public Mono<ResponseEntity<ApiResponse<ScheduledTask>>> updateTask(
            @PathVariable String taskId,
            @RequestBody ScheduledTask task) {
        return Mono.fromCallable(() -> {
            ScheduledTask updated = scheduleManagerService.updateTask(taskId, task);
            return ResponseEntity.ok(ApiResponse.success(updated));
        });
    }

    @DeleteMapping("/{taskId}")
    public Mono<ResponseEntity<ApiResponse<Void>>> deleteTask(@PathVariable String taskId) {
        return Mono.fromCallable(() -> {
            scheduleManagerService.deleteTask(taskId);
            return ResponseEntity.ok(ApiResponse.success(null));
        });
    }

    @GetMapping
    public Mono<ResponseEntity<ApiResponse<List<ScheduledTask>>>> listTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String namespace) {
        return Mono.fromCallable(() -> {
            IPage<ScheduledTask> result;
            if (namespace != null) {
                result = taskRepository.findByNamespace(namespace, page, size);
            } else {
                result = taskRepository.findAll(page, size);
            }
            ApiResponse.Pagination pagination = ApiResponse.Pagination.builder()
                    .total(result.getTotal())
                    .page((int) result.getCurrent())
                    .size((int) result.getSize())
                    .totalPages((int) result.getPages())
                    .build();
            return ResponseEntity.ok(ApiResponse.success(result.getRecords(), pagination));
        });
    }

    @PostMapping("/{taskId}/pause")
    public Mono<ResponseEntity<ApiResponse<Void>>> pauseTask(@PathVariable String taskId) {
        return Mono.fromCallable(() -> {
            scheduleManagerService.pauseTask(taskId);
            return ResponseEntity.ok(ApiResponse.success(null));
        });
    }

    @PostMapping("/{taskId}/resume")
    public Mono<ResponseEntity<ApiResponse<Void>>> resumeTask(@PathVariable String taskId) {
        return Mono.fromCallable(() -> {
            scheduleManagerService.resumeTask(taskId);
            return ResponseEntity.ok(ApiResponse.success(null));
        });
    }

    @PostMapping("/{taskId}/trigger")
    public Mono<ResponseEntity<ApiResponse<Void>>> triggerTask(
            @PathVariable String taskId,
            @RequestBody(required = false) Map<String, Object> context) {
        return Mono.fromCallable(() -> {
            scheduleManagerService.triggerTask(taskId, context != null ? context : Map.of());
            return ResponseEntity.ok(ApiResponse.success(null));
        });
    }

    @GetMapping("/upcoming")
    public Mono<ResponseEntity<ApiResponse<List<Map<String, Object>>>> getUpcomingExecutions(
            @RequestParam(defaultValue = "10") int limit) {
        return scheduleManagerService.getUpcomingExecutions(limit)
                .map(executions -> ResponseEntity.ok(ApiResponse.success(executions)));
    }
}
