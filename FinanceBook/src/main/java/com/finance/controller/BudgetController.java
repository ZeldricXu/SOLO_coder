package com.finance.controller;

import com.finance.dto.ApiResponse;
import com.finance.dto.BudgetSetRequest;
import com.finance.dto.BudgetSetResponse;
import com.finance.entity.Budget;
import com.finance.service.BudgetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/budgets")
@RequiredArgsConstructor
public class BudgetController {

    private final BudgetService budgetService;

    @PostMapping("/set")
    public ApiResponse<BudgetSetResponse> setBudget(@Valid @RequestBody BudgetSetRequest request) {
        BudgetSetResponse response = budgetService.setBudget(request);
        return ApiResponse.success(response);
    }

    @GetMapping("/account/{accountId}")
    public ApiResponse<List<Budget>> getBudgetsByAccount(@PathVariable String accountId) {
        List<Budget> budgets = budgetService.getBudgetsByAccount(accountId);
        return ApiResponse.success(budgets);
    }

    @GetMapping("/{budgetId}")
    public ApiResponse<Budget> getBudget(@PathVariable String budgetId) {
        Budget budget = budgetService.getBudgetById(budgetId);
        return ApiResponse.success(budget);
    }
}
