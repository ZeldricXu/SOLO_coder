package com.modelguard.controller;

import com.modelguard.common.ApiResponse;
import com.modelguard.common.PageResult;
import com.modelguard.dto.AbExperimentDTO;
import com.modelguard.dto.ExperimentResultRecordDTO;
import com.modelguard.dto.PromptVersionDTO;
import com.modelguard.entity.AbExperiment;
import com.modelguard.entity.AbExperimentResult;
import com.modelguard.entity.PromptVersion;
import com.modelguard.service.PromptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/prompt")
@RequiredArgsConstructor
public class PromptController {

    private final PromptService promptService;

    @PostMapping("/versions")
    public Mono<ApiResponse<PromptVersion>> createPromptVersion(@Valid @RequestBody PromptVersionDTO dto) {
        return promptService.createPromptVersion(dto)
                .map(ApiResponse::created);
    }

    @GetMapping("/{promptId}/versions/{version}")
    public Mono<ApiResponse<PromptVersion>> getPromptVersion(
            @PathVariable String promptId,
            @PathVariable Integer version) {
        return promptService.getPromptVersion(promptId, version)
                .map(ApiResponse::success);
    }

    @GetMapping("/{promptId}/versions/latest")
    public Mono<ApiResponse<PromptVersion>> getLatestPromptVersion(@PathVariable String promptId) {
        return promptService.getLatestPromptVersion(promptId)
                .map(ApiResponse::success);
    }

    @GetMapping("/{promptId}/versions")
    public Mono<ApiResponse<PageResult<PromptVersion>>> listPromptVersions(
            @PathVariable String promptId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return promptService.pagePromptVersions(promptId, pageNum, pageSize)
                .map(ApiResponse::success);
    }

    @PostMapping("/{promptId}/render")
    public Mono<ApiResponse<String>> renderPrompt(
            @PathVariable String promptId,
            @RequestParam(required = false) Integer version,
            @RequestBody(required = false) Map<String, Object> variables) {
        return promptService.renderPrompt(promptId, version, variables)
                .map(ApiResponse::success);
    }

    @PostMapping("/experiments")
    public Mono<ApiResponse<AbExperiment>> createAbExperiment(@Valid @RequestBody AbExperimentDTO dto) {
        return promptService.createAbExperiment(dto)
                .map(ApiResponse::created);
    }

    @GetMapping("/experiments/{experimentId}")
    public Mono<ApiResponse<AbExperiment>> getAbExperiment(@PathVariable String experimentId) {
        return promptService.getAbExperiment(experimentId)
                .map(ApiResponse::success);
    }

    @PostMapping("/experiments/{experimentId}/start")
    public Mono<ApiResponse<AbExperiment>> startAbExperiment(@PathVariable String experimentId) {
        return promptService.startAbExperiment(experimentId)
                .map(ApiResponse::success);
    }

    @PostMapping("/experiments/{experimentId}/pause")
    public Mono<ApiResponse<AbExperiment>> pauseAbExperiment(@PathVariable String experimentId) {
        return promptService.pauseAbExperiment(experimentId)
                .map(ApiResponse::success);
    }

    @PostMapping("/experiments/{experimentId}/stop")
    public Mono<ApiResponse<AbExperiment>> stopAbExperiment(@PathVariable String experimentId) {
        return promptService.stopAbExperiment(experimentId)
                .map(ApiResponse::success);
    }

    @GetMapping("/experiments")
    public Mono<ApiResponse<PageResult<AbExperiment>>> listAbExperiments(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return promptService.pageAbExperiments(status, pageNum, pageSize)
                .map(ApiResponse::success);
    }

    @PostMapping("/experiments/results")
    public Mono<ApiResponse<AbExperimentResult>> recordExperimentResult(
            @Valid @RequestBody ExperimentResultRecordDTO dto) {
        return promptService.recordExperimentResult(dto)
                .map(ApiResponse::success);
    }

    @GetMapping("/experiments/{experimentId}/results")
    public Mono<ApiResponse<List<AbExperimentResult>>> getExperimentResults(@PathVariable String experimentId) {
        return promptService.getExperimentResults(experimentId)
                .map(ApiResponse::success);
    }

    @GetMapping("/experiments/{experimentId}/compare")
    public Mono<ApiResponse<Map<String, Object>>> compareExperimentResults(@PathVariable String experimentId) {
        return promptService.compareExperimentResults(experimentId)
                .map(ApiResponse::success);
    }

    @GetMapping("/experiments/{experimentId}/assign")
    public Mono<ApiResponse<String>> assignExperimentGroup(
            @PathVariable String experimentId,
            @RequestParam String userId) {
        return promptService.assignExperimentGroup(experimentId, userId)
                .map(ApiResponse::success);
    }
}
