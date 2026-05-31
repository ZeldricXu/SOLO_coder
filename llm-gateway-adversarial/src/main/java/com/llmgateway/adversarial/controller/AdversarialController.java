package com.llmgateway.adversarial.controller;

import com.llmgateway.common.api.R;
import com.llmgateway.adversarial.entity.AdversarialAttack;
import com.llmgateway.adversarial.entity.AdversarialEvaluation;
import com.llmgateway.adversarial.entity.AdversarialPrompt;
import com.llmgateway.adversarial.service.AdversarialService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/adversarial")
@RequiredArgsConstructor
public class AdversarialController {

    private final AdversarialService adversarialService;

    @GetMapping("/attacks")
    public R<List<AdversarialAttack>> listAttacks(
            @RequestParam(required = false) String attackType,
            @RequestParam(required = false) String severity) {
        return R.success(adversarialService.listAttacks(attackType, severity));
    }

    @PostMapping("/prompts/generate")
    public R<List<AdversarialPrompt>> generatePrompts(@RequestBody Map<String, Object> request) {
        String attackId = (String) request.get("attackId");
        String originalPrompt = (String) request.get("originalPrompt");
        String targetModel = (String) request.get("targetModel");
        Integer count = request.get("count") != null ? ((Number) request.get("count")).intValue() : 5;
        return R.success(adversarialService.generatePrompts(attackId, originalPrompt, targetModel, count));
    }

    @GetMapping("/attacks/{attackId}/prompts")
    public R<List<AdversarialPrompt>> getPromptsByAttack(@PathVariable String attackId) {
        return R.success(adversarialService.getPromptsByAttack(attackId));
    }

    @PostMapping("/evaluations")
    public R<AdversarialEvaluation> runEvaluation(@RequestBody Map<String, Object> request) {
        String modelId = (String) request.get("modelId");
        String modelVersion = (String) request.get("modelVersion");
        @SuppressWarnings("unchecked")
        List<String> attackIds = (List<String>) request.get("attackIds");
        String createdBy = (String) request.get("createdBy");
        AdversarialEvaluation evaluation = adversarialService.runEvaluation(modelId, modelVersion, attackIds, createdBy);
        return R.created(evaluation);
    }

    @GetMapping("/evaluations/{evalId}")
    public R<AdversarialEvaluation> getEvaluation(@PathVariable String evalId) {
        return R.success(adversarialService.getEvaluation(evalId));
    }
}
