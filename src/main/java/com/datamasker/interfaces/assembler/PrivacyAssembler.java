package com.datamasker.interfaces.assembler;

import com.datamasker.domain.privacy.model.NoisyResult;
import com.datamasker.domain.privacy.model.PrivacyBudget;
import com.datamasker.interfaces.dto.privacy.AddNoiseResponse;
import com.datamasker.interfaces.dto.privacy.BudgetResponse;

public class PrivacyAssembler {

    public static AddNoiseResponse toAddNoiseResponse(NoisyResult noisyResult, double remainingBudget) {
        AddNoiseResponse response = new AddNoiseResponse();
        response.setOriginalValue(noisyResult.getOriginalValue());
        response.setNoiseAdded(noisyResult.getNoiseAdded());
        response.setNoisyValue(noisyResult.getNoisyValue());
        response.setEpsilon(noisyResult.getEpsilon());
        response.setMechanism(noisyResult.getMechanism());
        response.setQueryId(noisyResult.getQueryId());
        response.setRemainingBudget(remainingBudget);
        return response;
    }

    public static BudgetResponse toBudgetResponse(PrivacyBudget budget) {
        BudgetResponse response = new BudgetResponse();
        response.setTotalBudget(budget.getTotalBudget());
        response.setConsumedBudget(budget.getTotalBudget() - budget.getRemainingBudget());
        response.setRemainingBudget(budget.getRemainingBudget());
        return response;
    }
}
