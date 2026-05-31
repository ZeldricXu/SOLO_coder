package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("learning_path")
public class LearningPath extends TenantEntity {

    private String pathName;

    private String targetRole;

    private String description;

    private String skillSequence;

    private Long estimatedDuration;

    private Integer enabled;
}
