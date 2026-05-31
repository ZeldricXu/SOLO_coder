package com.solocoder.presentation.controller;

import com.solocoder.application.service.PromptExperimentService;
import com.solocoder.domain.model.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/prompts")
@RequiredArgsConstructor
public class PromptExperimentController {

    private final PromptExperimentService promptExperimentService;

    @PostMapping("/{promptName}/versions")
    public Mono<ApiResponse<String>> createPromptVersion(
            @PathVariable String promptName,
            @RequestBody @Valid Map<String, Object> request) {
        String content = (String) request.get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = (Map<String, Object>) request.getOrDefault("variables", Map.of());
        String createdBy = (String) request.getOrDefault("createdBy", "system");
        String description = (String) request.getOrDefault("description", "");

        return promptExperimentService.createPromptVersion(
                promptName, content, variables, createdBy, description);
    }

    @GetMapping("/{promptName}/content")
    public Mono<ApiResponse<String>> getPromptContent(
            @PathVariable String promptName,
            @RequestParam(required = false) String version) {
        return promptExperimentService.getPromptContent(promptName, version);
    }

    @GetMapping("/{promptName}/versions")
    public Mono<ApiResponse<Flux<Map<String, Object>>>> listPromptVersions(
            @PathVariable String promptName) {
        return promptExperimentService.listPromptVersions(promptName);
    }

    @PostMapping("/experiments")
    public Mono<ApiResponse<String>> createAbExperiment(
            @RequestBody @Valid Map<String, Object> request) {
        String experimentName = (String) request.get("experimentName");
        String promptName = (String) request.get("promptName");
        @SuppressWarnings("unchecked")
        List<String> versions = (List<String>) request.get("versions");
        @SuppressWarnings("unchecked")
        List<Double> trafficSplit = (List<Double>) request.get("trafficSplit");
        Instant startTime = request.containsKey("startTime")
                ? Instant.parse((String) request.get("startTime"))
                : Instant.now();
        Instant endTime = request.containsKey("endTime")
                ? Instant.parse((String) request.get("endTime"))
                : Instant.now().plusSeconds(86400 * 7);

        return promptExperimentService.createAbExperiment(
                experimentName, promptName, versions, trafficSplit, startTime, endTime);
    }

    @PostMapping("/experiments/{experimentId}/record")
    public Mono<ApiResponse<Void>> recordExperimentResult(
            @PathVariable String experimentId,
            @RequestBody @Valid Map<String, Object> request) {
        String version = (String) request.get("version");
        String requestId = (String) request.getOrDefault("requestId",
                "req_" + System.currentTimeMillis());
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) request.get("metrics");

        return promptExperimentService.recordExperimentResult(experimentId, version, requestId, metrics);
    }

    @GetMapping("/experiments/{experimentId}/results")
    public Mono<ApiResponse<Map<String, Object>>> getExperimentResults(
            @PathVariable String experimentId) {
        return promptExperimentService.getExperimentResults(experimentId);
    }

    @PostMapping("/{promptName}/compare")
    public Mono<ApiResponse<Map<String, Object>>> compareVersions(
            @PathVariable String promptName,
            @RequestBody @Valid Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> versions = (List<String>) request.get("versions");
        @SuppressWarnings("unchecked")
        List<String> metrics = (List<String>) request.getOrDefault("metrics",
                List.of("accuracy", "relevance", "completion_rate"));

        return promptExperimentService.compareVersions(promptName, versions, metrics);
    }

    @PostMapping("/{promptName}/default")
    public Mono<ApiResponse<Void>> setDefaultVersion(
            @PathVariable String promptName,
            @RequestParam String version) {
        return promptExperimentService.setDefaultVersion(promptName, version);
    }

    @PostMapping("/{promptName}/rollback")
    public Mono<ApiResponse<Void>> rollbackPrompt(
            @PathVariable String promptName,
            @RequestParam String targetVersion) {
        return promptExperimentService.rollbackPrompt(promptName, targetVersion);
    }
}
