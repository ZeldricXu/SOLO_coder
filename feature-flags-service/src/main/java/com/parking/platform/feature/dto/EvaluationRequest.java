package com.parking.platform.feature.dto;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class EvaluationRequest {
    private String flagKey;
    private String userId;
    private Map<String, String> attributes = new HashMap<>();
}
