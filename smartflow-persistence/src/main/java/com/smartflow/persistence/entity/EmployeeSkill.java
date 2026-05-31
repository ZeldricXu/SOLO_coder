package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_employee_skill")
public class EmployeeSkill extends BaseEntity {

    private Long employeeId;
    private String employeeName;
    private Long skillId;
    private String skillName;
    private Integer proficiency;
    private Integer experienceYears;
    private Integer certificationLevel;
    private String evaluationScore;
    private String learningPath;
}
