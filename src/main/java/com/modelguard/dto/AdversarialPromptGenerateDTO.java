package com.modelguard.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class AdversarialPromptGenerateDTO {

    private String originalPrompt;

    private String targetModel;

    private String targetVersion;

    private String attackStrategy;

    private List<String> attackTypes;

    private Map<String, Object> attackParameters;

    private Integer count;

    private String generatedBy;

    private Boolean evaluateImmediately;

    private Map<String, Object> metadata;
}
