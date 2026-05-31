package com.parking.platform.feature.dto;

import lombok.Data;

@Data
public class EvaluationResponse {
    private String flagKey;
    private boolean enabled;
    private String reason;
    private Double rolloutPercentage;
}
