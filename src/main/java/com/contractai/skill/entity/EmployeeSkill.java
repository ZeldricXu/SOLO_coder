package com.contractai.skill.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("employee_skill")
public class EmployeeSkill extends TenantBaseEntity {

    @TableField("employee_id")
    private Long employeeId;

    @TableField("skill_id")
    private Long skillId;

    @TableField("proficiency_level")
    private Integer proficiencyLevel;

    @TableField("certification_status")
    private Integer certificationStatus;

    @TableField("certification_date")
    private LocalDate certificationDate;

    @TableField("expire_date")
    private LocalDate expireDate;

    @TableField("last_assessed_at")
    private LocalDateTime lastAssessedAt;

    @TableField("assessment_score")
    private BigDecimal assessmentScore;
}
