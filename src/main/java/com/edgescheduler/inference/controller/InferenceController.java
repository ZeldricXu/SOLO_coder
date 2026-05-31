package com.edgescheduler.inference.controller;

import com.edgescheduler.common.dto.ApiResponse;
import com.edgescheduler.inference.dto.AiModelDTO;
import com.edgescheduler.inference.dto.InferenceTaskDTO;
import com.edgescheduler.inference.entity.AiModel;
import com.edgescheduler.inference.entity.InferenceTask;
import com.edgescheduler.inference.service.InferenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/inference")
@RequiredArgsConstructor
public class InferenceController {

    private final InferenceService inferenceService;

    @PostMapping("/models")
    public Mono<ApiResponse<AiModelDTO>> registerModel(@Valid @RequestBody AiModelDTO modelDTO) {
        return Mono.just(ApiResponse.created(inferenceService.registerModel(modelDTO)));
    }

    @GetMapping("/models/{modelId}")
    public Mono<ApiResponse<AiModelDTO>> getModel(@PathVariable String modelId) {
        return Mono.just(ApiResponse.success(inferenceService.getModel(modelId)));
    }

    @GetMapping("/models")
    public Mono<ApiResponse<List<AiModel>>> listModels(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String modelType) {
        return Mono.just(ApiResponse.success(inferenceService.listModels(status, modelType)));
    }

    @PutMapping("/models/{modelId}/status")
    public Mono<ApiResponse<AiModelDTO>> updateModelStatus(
            @PathVariable String modelId,
            @RequestBody Map<String, String> body) {
        String status = body.get("status");
        return Mono.just(ApiResponse.success(inferenceService.updateModelStatus(modelId, status)));
    }

    @DeleteMapping("/models/{modelId}")
    public Mono<ApiResponse<Void>> deleteModel(@PathVariable String modelId) {
        inferenceService.deleteModel(modelId);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/tasks")
    public Mono<ApiResponse<InferenceTaskDTO>> createTask(@Valid @RequestBody InferenceTaskDTO taskDTO) {
        return Mono.just(ApiResponse.created(inferenceService.createTask(taskDTO)));
    }

    @GetMapping("/tasks/{taskId}")
    public Mono<ApiResponse<InferenceTaskDTO>> getTask(@PathVariable String taskId) {
        return Mono.just(ApiResponse.success(inferenceService.getTask(taskId)));
    }

    @GetMapping("/tasks/{taskId}/status")
    public Mono<ApiResponse<Map<String, Object>>> getTaskStatus(@PathVariable String taskId) {
        return Mono.just(ApiResponse.success(inferenceService.getTaskStatus(taskId)));
    }

    @GetMapping("/tasks")
    public Mono<ApiResponse<List<InferenceTask>>> listTasks(
            @RequestParam(required = false) String deviceKey,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit) {
        return Mono.just(ApiResponse.success(inferenceService.listTasks(deviceKey, status, limit)));
    }

    @PostMapping("/tasks/{taskId}/schedule")
    public Mono<ApiResponse<InferenceTaskDTO>> scheduleTask(@PathVariable String taskId) {
        return Mono.just(ApiResponse.success(inferenceService.scheduleTask(taskId)));
    }

    @PostMapping("/tasks/{taskId}/execute")
    public Mono<ApiResponse<InferenceTaskDTO>> executeTask(@PathVariable String taskId) {
        return Mono.just(ApiResponse.success(inferenceService.executeTask(taskId)));
    }

    @PutMapping("/tasks/{taskId}/status")
    public Mono<ApiResponse<InferenceTaskDTO>> updateTaskStatus(
            @PathVariable String taskId,
            @RequestBody Map<String, Object> body) {
        String status = (String) body.get("status");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        Long inferenceTime = body.get("inferenceTimeMs") != null ?
                Long.valueOf(body.get("inferenceTimeMs").toString()) : null;
        return Mono.just(ApiResponse.success(inferenceService.updateTaskStatus(taskId, status, result, inferenceTime)));
    }

    @DeleteMapping("/tasks/{taskId}")
    public Mono<ApiResponse<Void>> cancelTask(@PathVariable String taskId) {
        inferenceService.cancelTask(taskId);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/tasks/schedule-batch")
    public Mono<ApiResponse<List<InferenceTask>>> schedulePendingTasks(
            @RequestParam(defaultValue = "10") int batchSize) {
        return Mono.just(ApiResponse.success(inferenceService.schedulePendingTasks(batchSize)));
    }

    @GetMapping("/devices/{deviceKey}/tasks")
    public Mono<ApiResponse<List<InferenceTask>>> getDeviceTasks(
            @PathVariable String deviceKey,
            @RequestParam(defaultValue = "20") int limit) {
        return Mono.just(ApiResponse.success(inferenceService.getDeviceTasks(deviceKey, limit)));
    }
}
