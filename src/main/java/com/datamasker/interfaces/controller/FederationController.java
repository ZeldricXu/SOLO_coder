package com.datamasker.interfaces.controller;

import com.datamasker.application.coordinator.FederationCoordinator;
import com.datamasker.application.service.FederationService;
import com.datamasker.domain.federation.model.FederationTask;
import com.datamasker.domain.federation.model.GlobalModelUpdate;
import com.datamasker.infrastructure.config.FederationConfig;
import com.datamasker.interfaces.assembler.FederationAssembler;
import com.datamasker.interfaces.dto.Result;
import com.datamasker.interfaces.dto.federation.CreateTaskRequest;
import com.datamasker.interfaces.dto.federation.CreateTaskResponse;
import com.datamasker.interfaces.dto.federation.GlobalModelResponse;
import com.datamasker.interfaces.dto.federation.SubmitGradientRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/federation")
@RequiredArgsConstructor
public class FederationController {

    private final FederationService federationService;
    private final FederationCoordinator federationCoordinator;
    private final FederationConfig federationConfig;

    @PostMapping("/tasks")
    public Result<CreateTaskResponse> createTask(@Valid @RequestBody CreateTaskRequest request) {
        int minParticipants = request.getMinParticipants() > 0
                ? request.getMinParticipants()
                : federationConfig.getMinParticipants();
        FederationTask task = federationService.createTask(minParticipants);
        return Result.success(FederationAssembler.toCreateTaskResponse(task));
    }

    @PostMapping("/tasks/{taskId}/gradients")
    public Result<Void> submitGradient(@PathVariable String taskId,
                                       @Valid @RequestBody SubmitGradientRequest request) {
        federationService.submitGradient(
                taskId,
                request.getParticipantId(),
                request.getEncryptedGradient(),
                request.getLocalModelHash(),
                request.getDataSampleCount()
        );
        return Result.success(null);
    }

    @PostMapping("/tasks/{taskId}/aggregate")
    public Result<GlobalModelResponse> aggregateAndUpdate(@PathVariable String taskId) {
        GlobalModelUpdate update = federationService.aggregateAndUpdate(taskId);
        boolean converged = federationCoordinator.isConverged(taskId);
        return Result.success(FederationAssembler.toGlobalModelResponse(update, converged));
    }

    @GetMapping("/tasks/{taskId}")
    public Result<CreateTaskResponse> getTaskInfo(@PathVariable String taskId) {
        FederationTask task = federationService.getTaskInfo(taskId);
        return Result.success(FederationAssembler.toCreateTaskResponse(task));
    }

    @GetMapping("/tasks/{taskId}/convergence")
    public Result<GlobalModelResponse> checkConvergence(@PathVariable String taskId) {
        FederationTask task = federationService.getTaskInfo(taskId);
        double convergence = federationService.checkConvergence(taskId);
        boolean converged = federationCoordinator.isConverged(taskId);

        GlobalModelResponse response = new GlobalModelResponse();
        response.setTaskId(taskId);
        response.setRoundNumber(task.getRoundNumber());
        response.setGlobalModelHash(task.getGlobalModelHash());
        response.setParticipantCount(task.getParticipantCount());
        response.setConvergenceMetric(convergence);
        response.setConverged(converged);
        return Result.success(response);
    }
}
