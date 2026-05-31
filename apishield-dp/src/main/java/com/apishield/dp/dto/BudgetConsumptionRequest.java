package com.apishield.dp.dto;

import lombok.Data;

@Data
public class BudgetConsumptionRequest {
    private String userId;
    private String dataSource;
    private double epsilon;
    private double delta;
}
