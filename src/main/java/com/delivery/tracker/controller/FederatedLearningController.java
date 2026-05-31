package com.delivery.tracker.controller;

import com.delivery.tracker.common.Result;
import com.delivery.tracker.entity.FLTrainingTask;
import com.delivery.tracker.service.FederatedLearningService;
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
    public Mono<Result<FLTrainingTask>> createTask(@RequestBody Map<String, Object> request) {
        String modelName = (String) request.get("modelName");
        int totalRounds = (Integer) request.get("totalRounds");
        @SuppressWarnings("unchecked")
        List<String> participants = (List<String>) request.get("participants");

        return flService.createTrainingTask(modelName, totalRounds, participants)
                .map(Result::success);
    }

    @GetMapping("/tasks")
    public Mono<Result<List<FLTrainingTask>>> getAllTasks() {
        return flService.getAllTasks()
                .collectList()
                .map(Result::success);
    }

    @GetMapping("/tasks/{taskId}")
    public Mono<Result<FLTrainingTask>> getTask(@PathVariable String taskId) {
        return flService.getTask(taskId)
                .map(Result::success);
    }

    @PostMapping("/tasks/{taskId}/start")
    public Mono<Result<FLTrainingTask>> startTraining(@PathVariable String taskId) {
        return flService.startTraining(taskId)
                .map(Result::success);
    }

    @PostMapping("/tasks/{taskId}/gradient")
    public Mono<Result<Map<String, Object>>> submitGradient(
            @PathVariable String taskId,
            @RequestBody Map<String, Object> request) {
        String participantId = (String) request.get("participantId");
        @SuppressWarnings("unchecked")
        List<Double> gradientList = (List<Double>) request.get("gradients");
        double[] gradients = gradientList.stream().mapToDouble(Double::doubleValue).toArray();

        return flService.submitGradient(taskId, participantId, gradients)
                .map(Result::success);
    }

    @PostMapping("/tasks/{taskId}/aggregate")
    public Mono<Result<Map<String, Object>>> aggregateAndUpdate(@PathVariable String taskId) {
        return flService.aggregateAndUpdate(taskId)
                .map(Result::success);
    }

    @GetMapping("/tasks/{taskId}/model")
    public Mono<Result<Map<String, Object>>> getGlobalModel(@PathVariable String taskId) {
        return flService.getGlobalModel(taskId)
                .map(model -> Result.success(Map.of(
                        "taskId", taskId,
                        "model", model
                )));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Mono<Result<Void>> cancelTask(@PathVariable String taskId) {
        return flService.cancelTask(taskId)
                .then(Mono.just(Result.success()));
    }
}
