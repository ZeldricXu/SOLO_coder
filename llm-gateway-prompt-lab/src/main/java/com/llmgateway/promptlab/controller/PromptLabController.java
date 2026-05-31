package com.llmgateway.promptlab.controller;

import com.llmgateway.common.api.R;
import com.llmgateway.common.api.PageResult;
import com.llmgateway.promptlab.entity.AbExperiment;
import com.llmgateway.promptlab.entity.PromptTemplate;
import com.llmgateway.promptlab.service.PromptLabService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/prompt-lab")
@RequiredArgsConstructor
public class PromptLabController {

    private final PromptLabService promptLabService;

    @PostMapping("/prompts")
    public R<PromptTemplate> createPrompt(@Valid @RequestBody PromptTemplate prompt) {
        return R.created(promptLabService.createPrompt(prompt));
    }

    @GetMapping("/prompts/{promptId}")
    public R<PromptTemplate> getPrompt(@PathVariable String promptId) {
        return R.success(promptLabService.getPrompt(promptId));
    }

    @GetMapping("/prompts")
    public R<PageResult<PromptTemplate>> listPrompts(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return R.success(promptLabService.listPrompts(status, pageNum, pageSize));
    }

    @PutMapping("/prompts/{promptId}")
    public R<PromptTemplate> updatePrompt(@PathVariable String promptId, @Valid @RequestBody PromptTemplate prompt) {
        return R.success(promptLabService.updatePrompt(promptId, prompt));
    }

    @DeleteMapping("/prompts/{promptId}")
    public R<Void> deletePrompt(@PathVariable String promptId) {
        promptLabService.deletePrompt(promptId);
        return R.success();
    }

    @PostMapping("/prompts/{promptId}/render")
    public R<String> renderPrompt(@PathVariable String promptId, @RequestBody Map<String, Object> variables) {
        return R.success(promptLabService.renderPrompt(promptId, variables));
    }

    @PostMapping("/experiments")
    public R<AbExperiment> createExperiment(@Valid @RequestBody AbExperiment experiment) {
        return R.created(promptLabService.createExperiment(experiment));
    }

    @GetMapping("/experiments/{experimentId}")
    public R<AbExperiment> getExperiment(@PathVariable String experimentId) {
        return R.success(promptLabService.getExperiment(experimentId));
    }

    @GetMapping("/experiments")
    public R<PageResult<AbExperiment>> listExperiments(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return R.success(promptLabService.listExperiments(status, pageNum, pageSize));
    }

    @PostMapping("/experiments/{experimentId}/start")
    public R<AbExperiment> startExperiment(@PathVariable String experimentId) {
        return R.success(promptLabService.startExperiment(experimentId));
    }

    @PostMapping("/experiments/{experimentId}/stop")
    public R<AbExperiment> stopExperiment(@PathVariable String experimentId) {
        return R.success(promptLabService.stopExperiment(experimentId));
    }

    @GetMapping("/experiments/{experimentId}/assign")
    public R<Map<String, String>> assignVariant(@PathVariable String experimentId, @RequestParam String userId) {
        String variant = promptLabService.assignVariant(experimentId, userId);
        return R.success(Map.of("variant", variant));
    }
}
