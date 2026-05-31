package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skill_category")
public class SkillCategory extends TenantEntity {

    private String categoryName;

    private String categoryCode;

    private Long parentId;

    private Integer sortOrder;

    private String description;
}
