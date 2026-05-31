package com.taskplatform.controller;

import com.taskplatform.adversarial.AdversarialService;
import com.taskplatform.common.response.ApiResponse;
import com.taskplatform.persistence.entity.AdversarialSample;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/adversarial")
@RequiredArgsConstructor
public class AdversarialController {

    private final AdversarialService adversarialService;

    @PostMapping("/generate")
    public ApiResponse<List<AdversarialSample>> generateSamples(@RequestBody Map<String, Object> request) {
        String originalPrompt = (String) request.get("originalPrompt");
        String targetModel = (String) request.getOrDefault("targetModel", "default");
        String createdBy = (String) request.getOrDefault("createdBy", "system");

        return ApiResponse.created(adversarialService.generateSamples(
                originalPrompt, targetModel, createdBy));
    }

    @PostMapping("/evaluate/{sampleId}")
    public ApiResponse<AdversarialSample> evaluateSample(
            @PathVariable String sampleId,
            @RequestBody Map<String, Object> request) {
        String modelResponse = (String) request.get("modelResponse");
        return ApiResponse.success(adversarialService.evaluateSample(sampleId, modelResponse));
    }

    @GetMapping("/assessment/{modelId}")
    public ApiResponse<Map<String, Object>> assessModelSecurity(@PathVariable String modelId) {
        return ApiResponse.success(adversarialService.assessModelSecurity(modelId));
    }
}
