package com.contractai.skill.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
public class SkillCreateDTO {
    private String skillCode;
    private String skillName;
    private Long categoryId;
    private Integer level;
    private String description;
    private List<Long> prerequisiteSkills;
    private Map<String, Object> learningPath;
    private Boolean certificationRequired;
}

@Data
class SkillCategoryCreateDTO {
    private String categoryCode;
    private String categoryName;
    private Long parentId;
    private Integer sortOrder;
    private String description;
}

@Data
class EmployeeCreateDTO {
    private String employeeNo;
    private String name;
    private String department;
    private String position;
    private String email;
    private String phone;
    private Map<String, Object> attributes;
}

@Data
class EmployeeSkillCreateDTO {
    private Long employeeId;
    private Long skillId;
    private Integer proficiencyLevel;
    private Integer certificationStatus;
    private LocalDate certificationDate;
    private LocalDate expireDate;
    private BigDecimal assessmentScore;
}

@Data
class LearningPathCreateDTO {
    private String pathCode;
    private String pathName;
    private String description;
    private Long targetSkillId;
    private Integer estimatedHours;
    private List<Map<String, Object>> courseSteps;
    private List<Long> prerequisitePaths;
}

@Data
class SkillAssessmentDTO {
    private Long employeeId;
    private Long skillId;
    private Integer proficiencyLevel;
    private BigDecimal assessmentScore;
    private String assessor;
}

@Data
class LearningRecommendationDTO {
    private Long employeeId;
    private Long targetSkillId;
    private String recommendationType;
}
