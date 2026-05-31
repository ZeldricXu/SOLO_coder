package com.contractai.skill.service;

import com.contractai.skill.entity.EmployeeSkill;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Component
public class SkillMatchCalculator {

    private static final BigDecimal ASSESSMENT_WEIGHT = BigDecimal.valueOf(0.6);
    private static final BigDecimal PROFICIENCY_WEIGHT = BigDecimal.valueOf(0.4);
    private static final BigDecimal MAX_PROFICIENCY = BigDecimal.valueOf(5);
    private static final BigDecimal SCALE_FACTOR = BigDecimal.valueOf(100);

    public BigDecimal calculateScore(EmployeeSkill employeeSkill) {
        if (employeeSkill == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal proficiencyWeight = BigDecimal.valueOf(employeeSkill.getProficiencyLevel())
                .divide(MAX_PROFICIENCY, 4, RoundingMode.HALF_UP);

        if (employeeSkill.getAssessmentScore() != null) {
            return employeeSkill.getAssessmentScore()
                    .multiply(ASSESSMENT_WEIGHT)
                    .add(proficiencyWeight.multiply(SCALE_FACTOR).multiply(PROFICIENCY_WEIGHT))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        return proficiencyWeight.multiply(SCALE_FACTOR).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal calculateAverageScore(Map<Long, BigDecimal> skillScores) {
        if (skillScores == null || skillScores.isEmpty()) {
            return BigDecimal.ZERO;
        }

        return skillScores.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(skillScores.size()), 2, RoundingMode.HALF_UP);
    }
}
