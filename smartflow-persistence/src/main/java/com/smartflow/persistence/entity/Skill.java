package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_skill")
public class Skill extends BaseEntity {

    private String skillName;
    private String skillCode;
    private Long parentId;
    private String parentPath;
    private Integer level;
    private Integer sortOrder;
    private String description;
    private String category;
    private Integer enabled;
}
