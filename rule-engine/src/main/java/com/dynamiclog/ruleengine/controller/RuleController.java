package com.dynamiclog.ruleengine.controller;

import com.dynamiclog.common.dto.ApiResponse;
import com.dynamiclog.common.entity.Rule;
import com.dynamiclog.common.event.DomainEvent;
import com.dynamiclog.ruleengine.service.RuleEngineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;

@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
public class RuleController {

    private final RuleEngineService ruleEngineService;

    @PostMapping
    public Mono<ApiResponse<Rule>> createRule(@RequestBody Rule rule) {
        return ruleEngineService.createRule(rule)
                .map(ApiResponse::success);
    }

    @GetMapping("/{ruleId}")
    public Mono<ApiResponse<Rule>> getRule(@PathVariable String ruleId) {
        return ruleEngineService.getRule(ruleId)
                .map(ApiResponse::success);
    }

    @GetMapping
    public Mono<ApiResponse<List<Rule>>> getRules(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String namespace) {
        Flux<Rule> rules = eventType != null ?
                ruleEngineService.getRulesByEventType(eventType) :
                ruleEngineService.getRulesByNamespace(namespace != null ? namespace : "default");
        return rules.collectList().map(ApiResponse::success);
    }

    @PutMapping("/{ruleId}")
    public Mono<ApiResponse<Rule>> updateRule(@PathVariable String ruleId, @RequestBody Rule rule) {
        return ruleEngineService.updateRule(ruleId, rule)
                .map(ApiResponse::success);
    }

    @DeleteMapping("/{ruleId}")
    public Mono<ApiResponse<Void>> deleteRule(@PathVariable String ruleId) {
        return ruleEngineService.deleteRule(ruleId)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @PostMapping("/evaluate")
    public Mono<ApiResponse<Void>> evaluateRules(@RequestBody DomainEvent event) {
        return ruleEngineService.evaluateRules(event)
                .then(Mono.just(ApiResponse.success(null)));
    }
}
