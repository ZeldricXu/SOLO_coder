package com.edgescheduler.ruleengine.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edgescheduler.common.dto.ApiResponse;
import com.edgescheduler.ruleengine.dto.RuleDTO;
import com.edgescheduler.ruleengine.dto.RuleTriggerRequest;
import com.edgescheduler.ruleengine.entity.Rule;
import com.edgescheduler.ruleengine.entity.RuleExecution;
import com.edgescheduler.ruleengine.service.RuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
public class RuleController {

    private final RuleService ruleService;

    @PostMapping
    public Mono<ApiResponse<RuleDTO>> createRule(@Valid @RequestBody RuleDTO ruleDTO) {
        return Mono.just(ApiResponse.created(ruleService.createRule(ruleDTO)));
    }

    @GetMapping("/{ruleId}")
    public Mono<ApiResponse<RuleDTO>> getRule(@PathVariable String ruleId) {
        return Mono.just(ApiResponse.success(ruleService.getRule(ruleId)));
    }

    @GetMapping
    public Mono<ApiResponse<IPage<RuleDTO>>> listRules(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String triggerType,
            @RequestParam(required = false) Integer enabled) {
        Page<Rule> pageParam = new Page<>(page, size);
        return Mono.just(ApiResponse.success(ruleService.listRules(pageParam, triggerType, enabled)));
    }

    @PutMapping("/{ruleId}")
    public Mono<ApiResponse<RuleDTO>> updateRule(
            @PathVariable String ruleId,
            @Valid @RequestBody RuleDTO ruleDTO) {
        return Mono.just(ApiResponse.success(ruleService.updateRule(ruleId, ruleDTO)));
    }

    @PutMapping("/{ruleId}/enabled")
    public Mono<ApiResponse<RuleDTO>> setRuleEnabled(
            @PathVariable String ruleId,
            @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        return Mono.just(ApiResponse.success(ruleService.setRuleEnabled(ruleId, enabled)));
    }

    @DeleteMapping("/{ruleId}")
    public Mono<ApiResponse<Void>> deleteRule(@PathVariable String ruleId) {
        ruleService.deleteRule(ruleId);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/trigger")
    public Mono<ApiResponse<RuleExecution>> triggerRule(@Valid @RequestBody RuleTriggerRequest request) {
        return Mono.just(ApiResponse.success(ruleService.triggerRule(request)));
    }

    @GetMapping("/executions/{runId}/status")
    public Mono<ApiResponse<RuleExecution>> getExecutionStatus(@PathVariable String runId) {
        return Mono.just(ApiResponse.success(ruleService.getExecutionStatus(runId)));
    }

    @GetMapping("/{ruleId}/executions")
    public Mono<ApiResponse<List<RuleExecution>>> getRuleExecutions(
            @PathVariable String ruleId,
            @RequestParam(defaultValue = "20") int limit) {
        return Mono.just(ApiResponse.success(ruleService.getRuleExecutions(ruleId, limit)));
    }

    @PostMapping("/evaluate")
    public Mono<ApiResponse<Map<String, Object>>> evaluateCondition(@RequestBody Map<String, Object> body) {
        String expression = (String) body.get("expression");
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) body.get("context");
        return Mono.just(ApiResponse.success(ruleService.evaluateCondition(expression, context)));
    }
}
