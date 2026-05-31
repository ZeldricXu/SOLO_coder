package com.modelguard.controller;

import com.modelguard.common.ApiResponse;
import com.modelguard.common.PageResult;
import com.modelguard.dto.request.AbExperimentCreateRequest;
import com.modelguard.dto.request.ExperimentResultRecordRequest;
import com.modelguard.dto.request.PromptVersionCreateRequest;
import com.modelguard.dto.response.AbExperimentResponse;
import com.modelguard.dto.response.AbExperimentResultResponse;
import com.modelguard.dto.response.ExperimentComparisonResponse;
import com.modelguard.dto.response.PromptVersionResponse;
import com.modelguard.service.prompt.PromptExperimentFacade;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/prompt-experiments")
@RequiredArgsConstructor
public class PromptExperimentController {

    private final PromptExperimentFacade promptExperimentFacade;

    @PostMapping("/prompts")
    @Timed(value = "prompt.version.create", description = "Time taken to create prompt version")
    public Mono<ResponseEntity<ApiResponse<PromptVersionResponse>>> createPromptVersion(
            @Valid @RequestBody PromptVersionCreateRequest request) {
        return promptExperimentFacade.createPromptVersion(request)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/prompts/{promptId}/versions/{version}")
    public Mono<ResponseEntity<ApiResponse<PromptVersionResponse>>> getPromptVersion(
            @PathVariable String promptId,
            @PathVariable Integer version) {
        return promptExperimentFacade.getPromptVersion(promptId, version)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/prompts/{promptId}/versions")
    public Mono<ResponseEntity<ApiResponse<List<PromptVersionResponse>>>> listPromptVersions(
            @PathVariable String promptId) {
        return promptExperimentFacade.listPromptVersions(promptId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/prompts/{promptId}/versions/page")
    public Mono<ResponseEntity<ApiResponse<PageResult<PromptVersionResponse>>>> pagePromptVersions(
            @PathVariable String promptId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return promptExperimentFacade.pagePromptVersions(promptId, pageNum, pageSize)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/prompts/{promptId}/render")
    public Mono<ResponseEntity<ApiResponse<String>>> renderPrompt(
            @PathVariable String promptId,
            @RequestParam(required = false) Integer version,
            @RequestBody Map<String, Object> variables) {
        return promptExperimentFacade.renderPrompt(promptId, version, variables)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/experiments")
    @Timed(value = "experiment.create", description = "Time taken to create AB experiment")
    public Mono<ResponseEntity<ApiResponse<AbExperimentResponse>>> createExperiment(
            @Valid @RequestBody AbExperimentCreateRequest request) {
        return promptExperimentFacade.createExperiment(request)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/experiments/{experimentId}")
    public Mono<ResponseEntity<ApiResponse<AbExperimentResponse>>> getExperiment(
            @PathVariable String experimentId) {
        return promptExperimentFacade.getExperiment(experimentId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/experiments/{experimentId}/start")
    public Mono<ResponseEntity<ApiResponse<AbExperimentResponse>>> startExperiment(
            @PathVariable String experimentId) {
        return promptExperimentFacade.startExperiment(experimentId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/experiments/{experimentId}/pause")
    public Mono<ResponseEntity<ApiResponse<AbExperimentResponse>>> pauseExperiment(
            @PathVariable String experimentId) {
        return promptExperimentFacade.pauseExperiment(experimentId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/experiments/{experimentId}/stop")
    public Mono<ResponseEntity<ApiResponse<AbExperimentResponse>>> stopExperiment(
            @PathVariable String experimentId) {
        return promptExperimentFacade.stopExperiment(experimentId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/experiments")
    public Mono<ResponseEntity<ApiResponse<PageResult<AbExperimentResponse>>>> pageExperiments(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return promptExperimentFacade.pageExperiments(status, pageNum, pageSize)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/experiments/{experimentId}/assign-group")
    public Mono<ResponseEntity<ApiResponse<String>>> assignGroup(
            @PathVariable String experimentId,
            @RequestParam String userId) {
        return promptExperimentFacade.assignGroup(experimentId, userId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/experiments/results")
    @Timed(value = "experiment.result.record", description = "Time taken to record experiment result")
    public Mono<ResponseEntity<ApiResponse<AbExperimentResultResponse>>> recordResult(
            @Valid @RequestBody ExperimentResultRecordRequest request) {
        return promptExperimentFacade.recordResult(request)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/experiments/{experimentId}/compare")
    public Mono<ResponseEntity<ApiResponse<ExperimentComparisonResponse>>> compareResults(
            @PathVariable String experimentId) {
        return promptExperimentFacade.compareResults(experimentId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/experiments/{experimentId}/validate")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> validateExperiment(
            @PathVariable String experimentId) {
        return promptExperimentFacade.validateExperiment(experimentId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }
}
