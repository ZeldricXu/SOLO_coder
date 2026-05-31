package com.taskflow.skill.domain;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 技能评估 - 领域模型
 */
@Data
@Builder
public class SkillAssessment {
    private String employeeId;
    private String skillId;
    private String skillName;
    private Integer currentLevel;
    private Integer targetLevel;
    private Integer proficiencyLevel;
    private String assessmentStatus;
    private LocalDateTime assessmentDate;
    private String assessor;
    private String notes;
    private String evidence;
}
