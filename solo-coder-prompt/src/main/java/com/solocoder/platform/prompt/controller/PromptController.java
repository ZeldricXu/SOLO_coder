package com.solocoder.platform.prompt.controller;

import com.solocoder.platform.common.model.ApiResponse;
import com.solocoder.platform.prompt.model.*;
import com.solocoder.platform.prompt.service.ExperimentService;
import com.solocoder.platform.prompt.service.PromptVersionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/prompt")
@RequiredArgsConstructor
public class PromptController {

    private final PromptVersionService versionService;
    private final ExperimentService experimentService;

    @PostMapping("/versions")
    public ApiResponse<PromptVersion> createVersion(@Valid @RequestBody PromptVersion version) {
        return ApiResponse.success(versionService.createVersion(version));
    }

    @GetMapping("/versions/{versionId}")
    public ApiResponse<PromptVersion> getVersion(@PathVariable String versionId) {
        return versionService.getVersion(versionId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "Version not found: " + versionId));
    }

    @GetMapping("/versions/prompt/{promptId}")
    public ApiResponse<List<PromptVersion>> getVersionsByPrompt(@PathVariable String promptId) {
        return ApiResponse.success(versionService.getVersionsByPrompt(promptId));
    }

    @GetMapping("/versions/prompt/{promptId}/latest")
    public ApiResponse<PromptVersion> getLatestVersion(@PathVariable String promptId) {
        return versionService.getLatestVersion(promptId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "No versions found for prompt: " + promptId));
    }

    @PostMapping("/versions/prompt/{promptId}/rollback/{targetVersion}")
    public ApiResponse<PromptVersion> rollback(@PathVariable String promptId, @PathVariable int targetVersion) {
        return ApiResponse.success(versionService.rollback(promptId, targetVersion));
    }

    @PostMapping("/experiments")
    public ApiResponse<ExperimentConfig> createExperiment(@Valid @RequestBody ExperimentConfig config) {
        return ApiResponse.success(experimentService.createExperiment(config));
    }

    @GetMapping("/experiments/{experimentId}")
    public ApiResponse<ExperimentConfig> getExperiment(@PathVariable String experimentId) {
        return experimentService.getExperiment(experimentId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "Experiment not found: " + experimentId));
    }

    @GetMapping("/experiments")
    public ApiResponse<List<ExperimentConfig>> listExperiments() {
        return ApiResponse.success(experimentService.listExperiments());
    }

    @PostMapping("/experiments/{experimentId}/start")
    public ApiResponse<ExperimentConfig> startExperiment(@PathVariable String experimentId) {
        return ApiResponse.success(experimentService.startExperiment(experimentId));
    }

    @PostMapping("/experiments/{experimentId}/pause")
    public ApiResponse<ExperimentConfig> pauseExperiment(@PathVariable String experimentId) {
        return ApiResponse.success(experimentService.pauseExperiment(experimentId));
    }

    @PostMapping("/experiments/results")
    public ApiResponse<ExperimentResult> recordResult(@Valid @RequestBody ExperimentResult result) {
        return ApiResponse.success(experimentService.recordResult(result));
    }

    @GetMapping("/experiments/{experimentId}/compare")
    public ApiResponse<ExperimentComparison> compareResults(@PathVariable String experimentId) {
        return ApiResponse.success(experimentService.compareResults(experimentId));
    }
}
