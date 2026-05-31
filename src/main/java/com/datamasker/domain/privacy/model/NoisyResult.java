package com.datamasker.domain.privacy.model;

import lombok.Data;

@Data
public class NoisyResult {

    private double originalValue;

    private double noiseAdded;

    private double noisyValue;

    private double epsilon;

    private String mechanism;

    private String queryId;
}
