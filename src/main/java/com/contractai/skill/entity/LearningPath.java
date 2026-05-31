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
@TableName("learning_path")
public class LearningPath extends TenantBaseEntity {

    @TableField("path_code")
    private String pathCode;

    @TableField("path_name")
    private String pathName;

    @TableField("description")
    private String description;

    @TableField("target_skill_id")
    private Long targetSkillId;

    @TableField("estimated_hours")
    private Integer estimatedHours;

    @TableField(value = "course_steps", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<Map<String, Object>> courseSteps;

    @TableField(value = "prerequisite_paths", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<Long> prerequisitePaths;
}
