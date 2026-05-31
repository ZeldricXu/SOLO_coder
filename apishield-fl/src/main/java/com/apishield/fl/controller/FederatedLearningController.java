package com.apishield.fl.controller;

import com.apishield.common.dto.Result;
import com.apishield.fl.domain.FlClientUpdate;
import com.apishield.fl.domain.FlTrainingTask;
import com.apishield.fl.dto.FlClientUpdateRequest;
import com.apishield.fl.dto.FlTaskRequest;
import com.apishield.fl.service.FederatedLearningService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/fl")
@RequiredArgsConstructor
public class FederatedLearningController {

    private final FederatedLearningService flService;

    @PostMapping("/tasks")
    public Mono<Result<FlTrainingTask>> createTask(@RequestBody FlTaskRequest request) {
        return Mono.just(Result.success(flService.createTask(request)));
    }

    @GetMapping("/tasks/{taskId}")
    public Mono<Result<FlTrainingTask>> getTask(@PathVariable String taskId) {
        return Mono.just(Result.success(flService.getTask(taskId)));
    }

    @PostMapping("/tasks/{taskId}/start")
    public Mono<Result<FlTrainingTask>> startTask(@PathVariable String taskId) {
        return Mono.just(Result.success(flService.startTask(taskId)));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Mono<Result<FlTrainingTask>> cancelTask(@PathVariable String taskId) {
        return Mono.just(Result.success(flService.cancelTask(taskId)));
    }

    @PostMapping("/updates")
    public Mono<Result<Void>> submitClientUpdate(@RequestBody FlClientUpdateRequest request) {
        flService.submitClientUpdate(request);
        return Mono.just(Result.success(null));
    }

    @GetMapping("/tasks/{taskId}/updates/{round}")
    public Mono<Result<List<FlClientUpdate>>> getClientUpdates(
            @PathVariable String taskId,
            @PathVariable int round) {
        return Mono.just(Result.success(flService.getClientUpdates(taskId, round)));
    }

    @PostMapping("/tasks/{taskId}/aggregate/{round}")
    public Mono<Result<FlTrainingTask>> aggregateGradients(
            @PathVariable String taskId,
            @PathVariable int round) {
        return Mono.just(Result.success(flService.aggregateGradients(taskId, round)));
    }

    @PostMapping("/tasks/{taskId}/update-model")
    public Mono<Result<FlTrainingTask>> updateGlobalModel(@PathVariable String taskId) {
        return Mono.just(Result.success(flService.updateGlobalModel(taskId)));
    }

    @GetMapping("/tasks/{taskId}/model")
    public Mono<Result<Map<String, Object>>> getGlobalModel(@PathVariable String taskId) {
        return Mono.just(Result.success(flService.getGlobalModel(taskId)));
    }

    @GetMapping("/tasks/status/{status}")
    public Mono<Result<List<FlTrainingTask>>> getTasksByStatus(@PathVariable FlTrainingTask.TaskStatus status) {
        return Mono.just(Result.success(flService.getTasksByStatus(status)));
    }
}
