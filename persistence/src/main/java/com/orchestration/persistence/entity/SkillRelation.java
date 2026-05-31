package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skill_relation")
public class SkillRelation extends TenantEntity {

    private Long skillId;

    private Long prerequisiteSkillId;

    private String relationType;
}
