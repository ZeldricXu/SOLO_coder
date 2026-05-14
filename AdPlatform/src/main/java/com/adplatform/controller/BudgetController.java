package com.adplatform.controller;

import com.adplatform.dto.ApiResponse;
import com.adplatform.entity.AdBudget;
import com.adplatform.service.BudgetService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/ads/{adId}/budget")
public class BudgetController {
    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @PostMapping
    public ApiResponse<AdBudget> createBudget(
            @PathVariable String adId,
            @RequestParam String budgetType,
            @RequestParam BigDecimal budgetAmount,
            @RequestParam(required = false) BigDecimal budgetThreshold) {
        AdBudget budget = budgetService.createBudget(adId, budgetType, budgetAmount, budgetThreshold);
        return ApiResponse.success(budget);
    }

    @GetMapping
    public ApiResponse<Optional<AdBudget>> getBudget(@PathVariable String adId) {
        Optional<AdBudget> budget = budgetService.getBudgetByAdId(adId);
        return ApiResponse.success(budget);
    }

    @GetMapping("/remaining")
    public ApiResponse<BigDecimal> getBudgetRemaining(@PathVariable String adId) {
        BigDecimal remaining = budgetService.getBudgetRemaining(adId);
        return ApiResponse.success(remaining);
    }

    @GetMapping("/check")
    public ApiResponse<Boolean> hasEnoughBudget(
            @PathVariable String adId,
            @RequestParam BigDecimal amount) {
        boolean enough = budgetService.hasEnoughBudget(adId, amount);
        return ApiResponse.success(enough);
    }
}
