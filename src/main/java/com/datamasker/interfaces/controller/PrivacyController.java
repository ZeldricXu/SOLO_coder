package com.datamasker.interfaces.controller;

import com.datamasker.application.service.DifferentialPrivacyService;
import com.datamasker.domain.privacy.model.NoisyResult;
import com.datamasker.domain.privacy.model.PrivacyBudget;
import com.datamasker.infrastructure.config.PrivacyConfig;
import com.datamasker.interfaces.dto.Result;
import com.datamasker.interfaces.dto.privacy.AddNoiseRequest;
import com.datamasker.interfaces.dto.privacy.AddNoiseResponse;
import com.datamasker.interfaces.dto.privacy.BudgetResponse;
import com.datamasker.interfaces.assembler.PrivacyAssembler;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/privacy")
@RequiredArgsConstructor
public class PrivacyController {

    private final DifferentialPrivacyService differentialPrivacyService;
    private final PrivacyConfig privacyConfig;

    @PostMapping("/noise")
    public Result<AddNoiseResponse> addNoise(@Valid @RequestBody AddNoiseRequest request) {
        NoisyResult result;
        if (request.getEpsilon() != null && request.getDelta() != null) {
            result = differentialPrivacyService.addNoiseToQuery(
                    request.getValue(), request.getSensitivity(),
                    request.getEpsilon(), request.getDelta(),
                    request.getMechanismType());
        } else {
            result = differentialPrivacyService.addNoiseToQuery(
                    request.getValue(), request.getSensitivity(),
                    request.getMechanismType());
        }
        double remainingBudget = differentialPrivacyService.getRemainingBudget();
        AddNoiseResponse response = PrivacyAssembler.toAddNoiseResponse(result, remainingBudget);
        return Result.success(response);
    }

    @GetMapping("/budget")
    public Result<BudgetResponse> getBudget() {
        double remaining = differentialPrivacyService.getRemainingBudget();
        PrivacyBudget budget = new PrivacyBudget();
        budget.setTotalBudget(privacyConfig.getMaxBudget());
        budget.setEpsilonConsumed(privacyConfig.getMaxBudget() - remaining);
        budget.setRemainingBudget(remaining);
        BudgetResponse response = PrivacyAssembler.toBudgetResponse(budget);
        return Result.success(response);
    }

    @PostMapping("/budget/reset")
    public Result<BudgetResponse> resetBudget() {
        differentialPrivacyService.resetBudget();
        double remaining = differentialPrivacyService.getRemainingBudget();
        PrivacyBudget budget = new PrivacyBudget();
        budget.setTotalBudget(privacyConfig.getMaxBudget());
        budget.setRemainingBudget(remaining);
        BudgetResponse response = PrivacyAssembler.toBudgetResponse(budget);
        return Result.success(response);
    }
}
