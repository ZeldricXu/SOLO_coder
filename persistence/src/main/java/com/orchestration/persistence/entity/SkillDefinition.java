package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skill_definition")
public class SkillDefinition extends TenantEntity {

    private String skillName;

    private String skillCode;

    private Long categoryId;

    private Integer skillLevel;

    private String description;

    private String knowledgePoints;

    private String evaluationCriteria;
}
