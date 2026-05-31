package com.taskflow.skill.domain;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 学习路径 - 领域模型
 */
@Data
@Builder
public class LearningPath {
    private String pathId;
    private String employeeId;
    private String targetSkillId;
    private String targetSkillName;
    private Integer targetLevel;
    private List<LearningStep> steps;
    private Integer estimatedHours;
    private Integer estimatedDays;
    private Map<String, Object> recommendations;
}
