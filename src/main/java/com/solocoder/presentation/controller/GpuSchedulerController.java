package com.solocoder.presentation.controller;

import com.solocoder.application.service.GpuSchedulerService;
import com.solocoder.domain.model.ApiResponse;
import com.solocoder.domain.model.RunInstance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/gpu")
@RequiredArgsConstructor
public class GpuSchedulerController {

    private final GpuSchedulerService gpuSchedulerService;

    @PostMapping("/tasks")
    public Mono<ApiResponse<RunInstance>> submitTask(
            @RequestBody @Valid Map<String, Object> request) {
        String taskName = (String) request.get("taskName");
        int priority = (int) request.getOrDefault("priority", 5);
        int gpuRequirement = (int) request.getOrDefault("gpuRequirement", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) request.getOrDefault("parameters", Map.of());

        Runnable task = () -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        return gpuSchedulerService.submitTask(taskName, priority, gpuRequirement, parameters, task);
    }

    @DeleteMapping("/tasks/{taskId}")
    public Mono<ApiResponse<Void>> cancelTask(@PathVariable String taskId) {
        return gpuSchedulerService.cancelTask(taskId);
    }

    @GetMapping("/tasks/{taskId}")
    public Mono<ApiResponse<RunInstance>> getTaskStatus(@PathVariable String taskId) {
        return gpuSchedulerService.getTaskStatus(taskId);
    }

    @GetMapping("/tasks")
    public Mono<ApiResponse<Flux<RunInstance>>> listTasks(
            @RequestParam(required = false) String status) {
        return gpuSchedulerService.listTasks(status);
    }

    @PostMapping("/tasks/{taskId}/preempt")
    public Mono<ApiResponse<Void>> preemptTask(@PathVariable String taskId) {
        return gpuSchedulerService.preemptTask(taskId);
    }

    @GetMapping("/cluster")
    public Mono<ApiResponse<Map<String, Object>>> getClusterStatus() {
        return gpuSchedulerService.getClusterStatus();
    }

    @PatchMapping("/tasks/{taskId}/priority")
    public Mono<ApiResponse<Void>> adjustTaskPriority(
            @PathVariable String taskId,
            @RequestParam @Min(1) @Max(10) int priority) {
        return gpuSchedulerService.adjustTaskPriority(taskId, priority);
    }
}
