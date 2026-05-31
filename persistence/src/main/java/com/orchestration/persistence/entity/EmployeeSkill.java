package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("employee_skill")
public class EmployeeSkill extends TenantEntity {

    private Long employeeId;

    private Long skillId;

    private Integer proficiencyLevel;

    private LocalDateTime lastEvaluatedAt;

    private Long evaluatorId;

    private String evaluationNote;

    private BigDecimal learningProgress;
}
