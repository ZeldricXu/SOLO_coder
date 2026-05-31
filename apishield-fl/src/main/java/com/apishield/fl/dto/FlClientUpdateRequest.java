package com.apishield.fl.dto;

import lombok.Data;
import java.util.Map;

@Data
public class FlClientUpdateRequest {
    private String taskId;
    private String clientId;
    private int roundNumber;
    private Map<String, Object> encryptedGradients;
    private Map<String, Object> encryptedWeights;
    private int sampleCount;
    private double localLoss;
}
