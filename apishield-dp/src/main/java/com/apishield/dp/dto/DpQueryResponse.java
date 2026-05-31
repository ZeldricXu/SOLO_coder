package com.apishield.dp.dto;

import lombok.Data;

@Data
public class DpQueryResponse {
    private String queryId;
    private double noisyResult;
    private double originalResult;
    private double epsilonConsumed;
    private double deltaConsumed;
    private double remainingEpsilon;
    private double remainingDelta;
    private String noiseType;
    private double noiseScale;
    private boolean budgetExceeded;
    private String message;
}
