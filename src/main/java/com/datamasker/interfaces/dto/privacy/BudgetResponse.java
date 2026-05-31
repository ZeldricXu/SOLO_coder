package com.datamasker.interfaces.dto.privacy;

import lombok.Data;

@Data
public class BudgetResponse {

    private double totalBudget;

    private double consumedBudget;

    private double remainingBudget;
}
