package com.edgescheduler.modules.rules.controller;

import com.edgescheduler.common.Result;
import com.edgescheduler.modules.rules.domain.RuleDefinition;
import com.edgescheduler.modules.rules.service.RuleEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
public class RuleEngineController {

    private final RuleEngineService ruleEngineService;

    @PostMapping
    public Mono<Result<RuleDefinition>> createRule(@RequestBody RuleDefinition rule) {
        return ruleEngineService.createRule(rule)
                .map(Result::success);
    }

    @GetMapping
    public Flux<Result<RuleDefinition>> getRules(
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) Boolean enabled) {
        return ruleEngineService.getRules(ruleType, enabled)
                .map(Result::success);
    }

    @GetMapping("/{ruleId}")
    public Mono<Result<RuleDefinition>> getRule(@PathVariable String ruleId) {
        return ruleEngineService.getRule(ruleId)
                .map(Result::success);
    }

    @PutMapping("/{ruleId}")
    public Mono<Result<RuleDefinition>> updateRule(
            @PathVariable String ruleId,
            @RequestBody RuleDefinition ruleUpdates) {
        return ruleEngineService.updateRule(ruleId, ruleUpdates)
                .map(Result::success);
    }

    @DeleteMapping("/{ruleId}")
    public Mono<Result<Void>> deleteRule(@PathVariable String ruleId) {
        return ruleEngineService.deleteRule(ruleId)
                .then(Mono.just(Result.success()));
    }

    @PutMapping("/{ruleId}/toggle")
    public Mono<Result<RuleDefinition>> toggleRule(
            @PathVariable String ruleId,
            @RequestParam boolean enabled) {
        return ruleEngineService.toggleRule(ruleId, enabled)
                .map(Result::success);
    }

    @PostMapping("/{ruleId}/evaluate")
    public Mono<Result<Map<String, Object>>> evaluateRule(
            @PathVariable String ruleId,
            @RequestBody Map<String, Object> context) {
        return ruleEngineService.evaluateRule(ruleId, context)
                .map(Result::success);
    }

    @PostMapping("/event")
    public Mono<Result<Map<String, Object>>> ingestEvent(@RequestBody Map<String, Object> event) {
        return ruleEngineService.ingestEvent(event)
                .map(Result::success);
    }

    @GetMapping("/stats")
    public Mono<Result<Map<String, Object>>> getRuleStats() {
        return ruleEngineService.getRuleStats()
                .map(Result::success);
    }

    @PostMapping("/reload")
    public Mono<Result<Map<String, Object>>> reloadRules() {
        return ruleEngineService.reloadRules()
                .map(Result::success);
    }
}
