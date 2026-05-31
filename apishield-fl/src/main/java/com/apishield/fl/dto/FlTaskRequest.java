package com.apishield.fl.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class FlTaskRequest {
    private String modelName;
    private String modelVersion;
    private List<String> participantIds;
    private int totalRounds;
    private Map<String, Object> hyperparameters;
    private Map<String, Object> initialModel;
}
