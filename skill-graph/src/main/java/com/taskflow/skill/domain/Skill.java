package com.taskflow.skill.domain;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 技能 - 领域模型
 */
@Data
@Builder
public class Skill {
    private String skillId;
    private String tenantId;
    private String name;
    private String code;
    private String description;
    private String category;
    private Integer level;
    private String parentId;
    private Integer sortOrder;
    private List<String> prerequisiteSkills;
}
