package com.edgescheduler.modules.inference.controller;

import com.edgescheduler.common.Result;
import com.edgescheduler.modules.inference.domain.AiModel;
import com.edgescheduler.modules.inference.domain.InferenceTask;
import com.edgescheduler.modules.inference.domain.ModelVersionRelease;
import com.edgescheduler.modules.inference.service.InferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/inference")
@RequiredArgsConstructor
public class InferenceController {

    private final InferenceService inferenceService;

    @PostMapping("/models")
    public Mono<Result<AiModel>> registerModel(@RequestBody AiModel model) {
        return inferenceService.registerModel(model)
                .map(Result::success);
    }

    @PostMapping("/models/{modelId}/versions")
    public Mono<Result<AiModel>> createNewVersion(
            @PathVariable String modelId,
            @RequestBody AiModel newVersion) {
        return inferenceService.createNewVersion(modelId, newVersion)
                .map(Result::success);
    }

    @PostMapping("/models/{modelId}/release")
    public Mono<Result<AiModel>> releaseModelVersion(
            @PathVariable String modelId,
            @RequestParam(required = false) String releaseType,
            @RequestParam(required = false) String releaseNotes,
            @RequestBody(required = false) List<String> grayscaleDevices) {
        return inferenceService.releaseModelVersion(modelId, releaseType, releaseNotes, grayscaleDevices)
                .map(Result::success);
    }

    @PostMapping("/models/{modelId}/rollback")
    public Mono<Result<AiModel>> rollbackModelVersion(@PathVariable String modelId) {
        return inferenceService.rollbackModelVersion(modelId)
                .map(Result::success);
    }

    @PostMapping("/models/{modelId}/deprecate")
    public Mono<Result<AiModel>> deprecateModelVersion(
            @PathVariable String modelId,
            @RequestParam(required = false) String reason) {
        return inferenceService.deprecateModelVersion(modelId, reason)
                .map(Result::success);
    }

    @PutMapping("/models/{modelName}/default")
    public Mono<Result<AiModel>> setDefaultVersion(
            @PathVariable String modelName,
            @RequestParam String version) {
        return inferenceService.setDefaultVersion(modelName, version)
                .map(Result::success);
    }

    @PostMapping("/models/{modelId}/deploy")
    public Mono<Result<AiModel>> deployModel(
            @PathVariable String modelId,
            @RequestParam String deviceId) {
        return inferenceService.deployModel(modelId, deviceId)
                .map(Result::success);
    }

    @GetMapping("/models")
    public Flux<Result<AiModel>> getModels(
            @RequestParam(required = false) String modelType) {
        return inferenceService.getModels(modelType)
                .map(Result::success);
    }

    @GetMapping("/models/{modelId}")
    public Mono<Result<AiModel>> getModel(@PathVariable String modelId) {
        return inferenceService.getModel(modelId)
                .map(Result::success);
    }

    @GetMapping("/models/{modelName}/versions")
    public Flux<Result<AiModel>> getAllModelVersions(@PathVariable String modelName) {
        return inferenceService.getAllModelVersions(modelName)
                .map(Result::success);
    }

    @GetMapping("/models/{modelName}/version-history")
    public Flux<Result<AiModel>> getModelVersionHistory(@PathVariable String modelName) {
        return inferenceService.getModelVersionHistory(modelName)
                .map(Result::success);
    }

    @GetMapping("/models/{modelName}/version-tree")
    public Mono<Result<Map<String, Object>>> getModelVersionTree(@PathVariable String modelName) {
        return inferenceService.getModelVersionTree(modelName)
                .map(Result::success);
    }

    @GetMapping("/models/{modelName}/default")
    public Mono<Result<AiModel>> getDefaultVersion(@PathVariable String modelName) {
        return inferenceService.getDefaultVersion(modelName)
                .map(Result::success);
    }

    @GetMapping("/models/{modelName}/{version}")
    public Mono<Result<AiModel>> getModelVersion(
            @PathVariable String modelName,
            @PathVariable String version) {
        return inferenceService.getModelVersion(modelName, version)
                .map(Result::success);
    }

    @GetMapping("/models/{modelId}/releases")
    public Flux<Result<ModelVersionRelease>> getReleaseHistory(@PathVariable String modelId) {
        return inferenceService.getReleaseHistory(modelId)
                .map(Result::success);
    }

    @PostMapping("/models/{modelId}/compatibility")
    public Mono<Result<Boolean>> checkCompatibility(
            @PathVariable String modelId,
            @RequestParam(required = false) String deviceId,
            @RequestBody Map<String, Object> runtimeEnv) {
        return inferenceService.checkCompatibility(modelId, deviceId, runtimeEnv)
                .map(Result::success);
    }

    @GetMapping("/models/{modelName}/stats")
    public Mono<Result<Map<String, Object>>> getVersionStats(@PathVariable String modelName) {
        return inferenceService.getVersionStats(modelName)
                .map(Result::success);
    }

    @PostMapping("/tasks")
    public Mono<Result<InferenceTask>> submitTask(
            @RequestParam String modelId,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) Integer priority,
            @RequestBody Map<String, Object> inputData) {
        return inferenceService.submitTask(modelId, deviceId, inputData, priority)
                .map(Result::success);
    }

    @GetMapping("/tasks/{taskId}")
    public Mono<Result<InferenceTask>> getTaskResult(@PathVariable String taskId) {
        return inferenceService.getTaskResult(taskId)
                .map(Result::success);
    }

    @GetMapping("/tasks")
    public Flux<Result<InferenceTask>> getTasks(
            @RequestParam(required = false) String status) {
        return inferenceService.getTasksByStatus(status)
                .map(Result::success);
    }

    @DeleteMapping("/tasks/{taskId}")
    public Mono<Result<Void>> cancelTask(@PathVariable String taskId) {
        return inferenceService.cancelTask(taskId)
                .then(Mono.just(Result.success()));
    }

    @GetMapping("/queue-status")
    public Mono<Result<Map<String, Object>>> getQueueStatus() {
        return inferenceService.getQueueStatus()
                .map(Result::success);
    }
}
