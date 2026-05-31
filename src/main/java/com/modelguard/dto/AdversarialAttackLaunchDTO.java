package com.modelguard.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class AdversarialAttackLaunchDTO {

    private String attackName;

    private String targetModel;

    private String targetVersion;

    private List<String> attackStrategies;

    private List<String> originalPrompts;

    private Map<String, Object> attackConfig;

    private Integer promptsPerStrategy;

    private String initiatedBy;
}
