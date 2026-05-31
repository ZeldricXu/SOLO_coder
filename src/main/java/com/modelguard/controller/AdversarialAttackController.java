package com.modelguard.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.common.ApiResponse;
import com.modelguard.dto.AdversarialAttackLaunchDTO;
import com.modelguard.dto.AdversarialPromptGenerateDTO;
import com.modelguard.dto.SecurityAssessmentCreateDTO;
import com.modelguard.entity.AdversarialAttack;
import com.modelguard.entity.AdversarialPrompt;
import com.modelguard.entity.SecurityAssessment;
import com.modelguard.service.AdversarialAttackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/adversarial")
@RequiredArgsConstructor
public class AdversarialAttackController {

    private final AdversarialAttackService adversarialAttackService;

    @PostMapping("/prompts/generate")
    public Mono<ApiResponse<AdversarialPrompt>> generatePrompt(@RequestBody AdversarialPromptGenerateDTO dto) {
        return adversarialAttackService.generateAdversarialPrompt(dto)
                .map(ApiResponse::success);
    }

    @PostMapping("/prompts/generate/batch")
    public Mono<ApiResponse<List<AdversarialPrompt>>> generateMultiplePrompts(@RequestBody AdversarialPromptGenerateDTO dto) {
        return adversarialAttackService.generateMultiplePrompts(dto)
                .map(ApiResponse::success);
    }

    @GetMapping("/prompts/{promptId}")
    public Mono<ApiResponse<AdversarialPrompt>> getPrompt(@PathVariable String promptId) {
        return adversarialAttackService.getAdversarialPrompt(promptId)
                .map(ApiResponse::success);
    }

    @GetMapping("/prompts")
    public Mono<ApiResponse<Page<AdversarialPrompt>>> listPrompts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String targetModel,
            @RequestParam(required = false) String attackType,
            @RequestParam(required = false) String attackSuccess,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String category) {
        return adversarialAttackService.listAdversarialPrompts(page, size, targetModel, attackType, attackSuccess, severity, category)
                .map(ApiResponse::success);
    }

    @DeleteMapping("/prompts/{promptId}")
    public Mono<ApiResponse<Void>> deletePrompt(@PathVariable String promptId) {
        return adversarialAttackService.deleteAdversarialPrompt(promptId)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @PostMapping("/prompts/{promptId}/evaluate")
    public Mono<ApiResponse<AdversarialPrompt>> evaluatePrompt(
            @PathVariable String promptId,
            @RequestBody Map<String, Object> modelResponse) {
        return adversarialAttackService.evaluatePrompt(promptId, modelResponse)
                .map(ApiResponse::success);
    }

    @PostMapping("/attacks/launch")
    public Mono<ApiResponse<AdversarialAttack>> launchAttack(@RequestBody AdversarialAttackLaunchDTO dto) {
        return adversarialAttackService.launchAttack(dto)
                .map(ApiResponse::success);
    }

    @GetMapping("/attacks/{attackId}")
    public Mono<ApiResponse<AdversarialAttack>> getAttack(@PathVariable String attackId) {
        return adversarialAttackService.getAttack(attackId)
                .map(ApiResponse::success);
    }

    @GetMapping("/attacks")
    public Mono<ApiResponse<Page<AdversarialAttack>>> listAttacks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String targetModel,
            @RequestParam(required = false) String status) {
        return adversarialAttackService.listAttacks(page, size, targetModel, status)
                .map(ApiResponse::success);
    }

    @GetMapping("/strategies")
    public ApiResponse<List<String>> getAttackStrategies() {
        return ApiResponse.success(adversarialAttackService.getAvailableAttackStrategies());
    }

    @GetMapping("/strategies/{strategy}")
    public ApiResponse<Map<String, Object>> getStrategyDetails(@PathVariable String strategy) {
        return ApiResponse.success(adversarialAttackService.getAttackStrategyDetails(strategy));
    }

    @PostMapping("/assessments")
    public Mono<ApiResponse<SecurityAssessment>> createAssessment(@RequestBody SecurityAssessmentCreateDTO dto) {
        return adversarialAttackService.createSecurityAssessment(dto)
                .map(ApiResponse::success);
    }

    @GetMapping("/assessments/{assessmentId}")
    public Mono<ApiResponse<SecurityAssessment>> getAssessment(@PathVariable String assessmentId) {
        return adversarialAttackService.getSecurityAssessment(assessmentId)
                .map(ApiResponse::success);
    }

    @GetMapping("/assessments")
    public Mono<ApiResponse<Page<SecurityAssessment>>> listAssessments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String modelId,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String status) {
        return adversarialAttackService.listSecurityAssessments(page, size, modelId, riskLevel, status)
                .map(ApiResponse::success);
    }

    @GetMapping("/models/{modelId}/security-summary")
    public Mono<ApiResponse<Map<String, Object>>> getModelSecuritySummary(
            @PathVariable String modelId,
            @RequestParam(required = false) String version) {
        return adversarialAttackService.getModelSecuritySummary(modelId, version)
                .map(ApiResponse::success);
    }

    @PostMapping("/tools/generate/prompt-injection")
    public ApiResponse<String> generatePromptInjection(
            @RequestParam(required = false) String originalPrompt,
            @RequestBody(required = false) Map<String, Object> params) {
        return ApiResponse.success(adversarialAttackService.generatePromptInjection(originalPrompt,
                params != null ? params : Map.of()));
    }

    @PostMapping("/tools/generate/jailbreak")
    public ApiResponse<String> generateJailbreak(
            @RequestParam(required = false) String originalPrompt,
            @RequestBody(required = false) Map<String, Object> params) {
        return ApiResponse.success(adversarialAttackService.generateJailbreakPrompt(originalPrompt,
                params != null ? params : Map.of()));
    }

    @PostMapping("/tools/generate/roleplay")
    public ApiResponse<String> generateRoleplay(
            @RequestParam(required = false) String originalPrompt,
            @RequestBody(required = false) Map<String, Object> params) {
        return ApiResponse.success(adversarialAttackService.generateRoleplayAttack(originalPrompt,
                params != null ? params : Map.of()));
    }

    @PostMapping("/tools/generate/obfuscation")
    public ApiResponse<String> generateObfuscation(
            @RequestParam(required = false) String originalPrompt,
            @RequestBody(required = false) Map<String, Object> params) {
        return ApiResponse.success(adversarialAttackService.generateObfuscationAttack(originalPrompt,
                params != null ? params : Map.of()));
    }

    @PostMapping("/tools/generate/data-leakage")
    public ApiResponse<String> generateDataLeakage(
            @RequestParam(required = false) String originalPrompt,
            @RequestBody(required = false) Map<String, Object> params) {
        return ApiResponse.success(adversarialAttackService.generateDataLeakageAttack(originalPrompt,
                params != null ? params : Map.of()));
    }

    @PostMapping("/tools/generate/adversarial-suffix")
    public ApiResponse<String> generateAdversarialSuffix(
            @RequestParam(required = false) String originalPrompt,
            @RequestBody(required = false) Map<String, Object> params) {
        return ApiResponse.success(adversarialAttackService.generateAdversarialSuffix(originalPrompt,
                params != null ? params : Map.of()));
    }

    @PostMapping("/tools/extract-sensitive")
    public ApiResponse<Map<String, Object>> extractSensitiveData(@RequestBody Map<String, Object> modelResponse) {
        return ApiResponse.success(adversarialAttackService.extractSensitiveData(modelResponse));
    }
}
