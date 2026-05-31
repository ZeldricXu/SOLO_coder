package com.taskflow.data.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskflow.common.model.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skill")
public class SkillEntity extends TenantEntity {

    @TableField("skill_id")
    private String skillId;

    @TableField("name")
    private String name;

    @TableField("code")
    private String code;

    @TableField("description")
    private String description;

    @TableField("category")
    private String category;

    @TableField("level")
    private Integer level;

    @TableField("parent_id")
    private String parentId;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("prerequisites")
    private String prerequisites;
}
