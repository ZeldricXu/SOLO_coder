package com.taskflow.skill.domain;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 学习步骤 - 领域模型
 */
@Data
@Builder
public class LearningStep {
    private String stepId;
    private String skillId;
    private String skillName;
    private Integer targetLevel;
    private Integer currentLevel;
    private String difficulty;
    private Integer estimatedHours;
    private List<String> prerequisites;
    private List<String> learningResources;
    private List<String> assessmentMethods;
}
