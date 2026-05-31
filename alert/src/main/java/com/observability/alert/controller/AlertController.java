package com.observability.alert.controller;

import com.observability.alert.dto.AlertRuleCreateRequest;
import com.observability.alert.entity.AlertRuleEntity;
import com.observability.alert.service.AlertService;
import com.observability.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    @PostMapping("/rules")
    public Mono<ApiResponse<AlertRuleEntity>> createRule(@RequestBody AlertRuleCreateRequest request) {
        return alertService.createRule(
                request.getName(),
                request.getMetricName(),
                request.getExpression(),
                request.getLevel(),
                request.getThreshold(),
                request.getDuration(),
                request.getNotificationConfig()
        ).map(ApiResponse::success);
    }

    @GetMapping("/rules")
    public Mono<ApiResponse<List<AlertRuleEntity>>> listRules() {
        return alertService.listRules()
                .map(ApiResponse::success);
    }

    @DeleteMapping("/rules/{alertId}")
    public Mono<ApiResponse<String>> deleteRule(@PathVariable String alertId) {
        return alertService.deleteRule(alertId)
                .then(Mono.just(ApiResponse.success("Alert rule deleted successfully")));
    }

    @PostMapping("/evaluate")
    public Mono<ApiResponse<Map<String, Boolean>>> evaluate(@RequestBody Map<String, Double> metrics) {
        return alertService.evaluateAllRules(metrics)
                .map(ApiResponse::success);
    }

    @PostMapping("/rules/{alertId}/evaluate")
    public Mono<ApiResponse<Boolean>> evaluateRule(
            @PathVariable String alertId,
            @RequestBody Map<String, Double> metrics) {
        return alertService.evaluateRule(alertId, metrics)
                .map(ApiResponse::success);
    }
}
