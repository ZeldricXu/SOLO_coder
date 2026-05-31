package com.contractai.skill.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skill")
public class Skill extends TenantBaseEntity {

    @TableField("skill_code")
    private String skillCode;

    @TableField("skill_name")
    private String skillName;

    @TableField("category_id")
    private Long categoryId;

    @TableField("level")
    private Integer level;

    @TableField("description")
    private String description;

    @TableField(value = "prerequisite_skills", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<Long> prerequisiteSkills;

    @TableField(value = "learning_path", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> learningPath;

    @TableField("certification_required")
    private Integer certificationRequired;
}
