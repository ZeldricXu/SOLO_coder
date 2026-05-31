package com.taskplatform.controller;

import com.taskplatform.common.response.ApiResponse;
import com.taskplatform.persistence.entity.Experiment;
import com.taskplatform.persistence.entity.PromptVersion;
import com.taskplatform.prompt.ExperimentService;
import com.taskplatform.prompt.PromptService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/prompts")
@RequiredArgsConstructor
public class PromptController {

    private final PromptService promptService;
    private final ExperimentService experimentService;

    @PostMapping
    public ApiResponse<PromptVersion> createPrompt(@RequestBody Map<String, Object> request) {
        PromptVersion prompt = new PromptVersion();
        prompt.setContent((String) request.get("content"));
        prompt.setTemplate((String) request.get("template"));
        prompt.setName((String) request.get("name"));
        prompt.setModelId((String) request.get("modelId"));
        prompt.setTemperature(request.get("temperature") != null ?
                ((Number) request.get("temperature")).doubleValue() : 0.7);
        prompt.setMaxTokens(request.get("maxTokens") != null ?
                ((Number) request.get("maxTokens")).intValue() : 2048);
        String createdBy = (String) request.getOrDefault("createdBy", "system");

        return ApiResponse.created(promptService.createPrompt(prompt, createdBy));
    }

    @GetMapping("/{promptId}")
    public ApiResponse<PromptVersion> getPrompt(
            @PathVariable String promptId,
            @RequestParam(required = false) Integer version) {
        return ApiResponse.success(promptService.getPrompt(promptId, version));
    }

    @GetMapping("/{promptId}/versions")
    public ApiResponse<List<PromptVersion>> listVersions(@PathVariable String promptId) {
        return ApiResponse.success(promptService.listPromptVersions(promptId));
    }

    @PostMapping("/{promptId}/render")
    public ApiResponse<String> renderPrompt(
            @PathVariable String promptId,
            @RequestBody(required = false) Map<String, Object> context) {
        return ApiResponse.success(promptService.renderPrompt(promptId, context));
    }

    @PostMapping("/experiments")
    public ApiResponse<Experiment> createExperiment(@RequestBody Map<String, Object> request) {
        Experiment experiment = new Experiment();
        experiment.setName((String) request.get("name"));
        experiment.setDescription((String) request.get("description"));
        experiment.setControlPromptId((String) request.get("controlPromptId"));
        experiment.setTreatmentPromptIds(
                com.taskplatform.common.util.JsonUtil.toJson(request.get("treatmentPromptIds")));
        experiment.setTrafficSplit(
                com.taskplatform.common.util.JsonUtil.toJson(request.get("trafficSplit")));
        String createdBy = (String) request.getOrDefault("createdBy", "system");

        return ApiResponse.created(experimentService.createExperiment(experiment, createdBy));
    }

    @PostMapping("/experiments/{experimentId}/start")
    public ApiResponse<Experiment> startExperiment(@PathVariable String experimentId) {
        return ApiResponse.success(experimentService.startExperiment(experimentId));
    }

    @PostMapping("/experiments/{experimentId}/stop")
    public ApiResponse<Experiment> stopExperiment(@PathVariable String experimentId) {
        return ApiResponse.success(experimentService.stopExperiment(experimentId));
    }

    @GetMapping("/experiments/{experimentId}/evaluate")
    public ApiResponse<Map<String, Object>> evaluateExperiment(@PathVariable String experimentId) {
        return ApiResponse.success(experimentService.evaluateExperiment(experimentId));
    }
}
