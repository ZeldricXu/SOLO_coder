package com.datamasker.interfaces.dto.privacy;

import lombok.Data;

@Data
public class AddNoiseResponse {

    private double originalValue;

    private double noiseAdded;

    private double noisyValue;

    private double epsilon;

    private String mechanism;

    private String queryId;

    private double remainingBudget;
}
