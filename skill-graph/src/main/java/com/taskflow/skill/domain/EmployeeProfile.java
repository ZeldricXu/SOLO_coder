package com.taskflow.skill.domain;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 员工技能档案 - 领域模型
 */
@Data
@Builder
public class EmployeeProfile {
    private String employeeId;
    private String employeeName;
    private Map<String, Integer> skillProficiencies;
    private List<String> strongSkills;
    private List<String> weakSkills;
    private Map<String, Double> categoryScores;
    private Double overallScore;
    private Integer totalSkills;
    private Integer certifiedSkills;
    private List<String> recommendedSkills;
}
