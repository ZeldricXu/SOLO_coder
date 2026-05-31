package com.taskflow.data.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskflow.common.model.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("employee_skill")
public class EmployeeSkillEntity extends TenantEntity {

    @TableField("employee_id")
    private String employeeId;

    @TableField("skill_id")
    private String skillId;

    @TableField("proficiency_level")
    private Integer proficiencyLevel;

    @TableField("assessment_date")
    private LocalDateTime assessmentDate;

    @TableField("assessor")
    private String assessor;

    @TableField("evidence")
    private String evidence;

    @TableField("notes")
    private String notes;
}
