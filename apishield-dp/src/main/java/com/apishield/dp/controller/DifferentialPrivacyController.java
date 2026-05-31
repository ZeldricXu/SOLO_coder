package com.apishield.dp.controller;

import com.apishield.common.dto.Result;
import com.apishield.dp.domain.DpQueryLog;
import com.apishield.dp.domain.PrivacyBudget;
import com.apishield.dp.dto.BudgetConsumptionRequest;
import com.apishield.dp.dto.DpQueryRequest;
import com.apishield.dp.dto.DpQueryResponse;
import com.apishield.dp.service.DifferentialPrivacyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dp")
@RequiredArgsConstructor
public class DifferentialPrivacyController {

    private final DifferentialPrivacyService dpService;

    @PostMapping("/query")
    public Mono<Result<DpQueryResponse>> executeQuery(@RequestBody DpQueryRequest request) {
        return Mono.just(Result.success(dpService.executeQuery(request)));
    }

    @PostMapping("/budgets")
    public Mono<Result<PrivacyBudget>> createBudget(@RequestBody Map<String, Object> request) {
        return Mono.just(Result.success(dpService.createBudget(
                (String) request.get("userId"),
                (String) request.get("dataSource"),
                ((Number) request.getOrDefault("totalEpsilon", 10.0)).doubleValue(),
                ((Number) request.getOrDefault("totalDelta", 0.0001)).doubleValue(),
                (String) request.getOrDefault("resetPeriod", "DAILY"),
                (Boolean) request.getOrDefault("autoReset", true)
        )));
    }

    @GetMapping("/budgets/{budgetId}")
    public Mono<Result<PrivacyBudget>> getBudget(@PathVariable String budgetId) {
        return Mono.just(Result.success(dpService.getBudget(budgetId)));
    }

    @GetMapping("/budgets/user/{userId}")
    public Mono<Result<List<PrivacyBudget>>> getBudgetsByUser(@PathVariable String userId) {
        return Mono.just(Result.success(dpService.getBudgetsByUser(userId)));
    }

    @GetMapping("/budgets/user/{userId}/dataSource/{dataSource}")
    public Mono<Result<PrivacyBudget>> getBudgetByUserAndDataSource(
            @PathVariable String userId,
            @PathVariable String dataSource) {
        return Mono.just(Result.success(dpService.getBudgetByUserAndDataSource(userId, dataSource)));
    }

    @PostMapping("/budgets/consume")
    public Mono<Result<Boolean>> consumeBudget(@RequestBody BudgetConsumptionRequest request) {
        return Mono.just(Result.success(dpService.consumeBudget(request)));
    }

    @PostMapping("/budgets/{budgetId}/reset")
    public Mono<Result<PrivacyBudget>> resetBudget(@PathVariable String budgetId) {
        return Mono.just(Result.success(dpService.resetBudget(budgetId)));
    }

    @GetMapping("/logs/{logId}")
    public Mono<Result<DpQueryLog>> getQueryLog(@PathVariable String logId) {
        return Mono.just(Result.success(dpService.getQueryLog(logId)));
    }

    @GetMapping("/logs/user/{userId}")
    public Mono<Result<List<DpQueryLog>>> getQueryLogsByUser(
            @PathVariable String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Mono.just(Result.success(dpService.getQueryLogsByUser(userId, page, size)));
    }

    @GetMapping("/logs/dataSource/{dataSource}")
    public Mono<Result<List<DpQueryLog>>> getQueryLogsByDataSource(
            @PathVariable String dataSource,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Mono.just(Result.success(dpService.getQueryLogsByDataSource(dataSource, page, size)));
    }

    @GetMapping("/noise")
    public Mono<Result<Double>> calculateNoise(
            @RequestParam String noiseType,
            @RequestParam double sensitivity,
            @RequestParam double epsilon,
            @RequestParam(defaultValue = "0.0001") double delta) {
        return Mono.just(Result.success(dpService.calculateNoise(noiseType, sensitivity, epsilon, delta)));
    }

    @GetMapping("/budget-check")
    public Mono<Result<Boolean>> hasSufficientBudget(
            @RequestParam String userId,
            @RequestParam String dataSource,
            @RequestParam double epsilon,
            @RequestParam(defaultValue = "0.0001") double delta) {
        return Mono.just(Result.success(dpService.hasSufficientBudget(userId, dataSource, epsilon, delta)));
    }
}
