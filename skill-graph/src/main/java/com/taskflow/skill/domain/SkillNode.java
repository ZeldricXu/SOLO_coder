package com.taskflow.skill.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能节点（用于技能树）- 领域模型
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SkillNode extends Skill {
    private List<SkillNode> children = new ArrayList<>();

    public SkillNode() {
        super();
    }

    public static SkillNode fromSkill(Skill skill) {
        SkillNode node = new SkillNode();
        node.setSkillId(skill.getSkillId());
        node.setTenantId(skill.getTenantId());
        node.setName(skill.getName());
        node.setCode(skill.getCode());
        node.setDescription(skill.getDescription());
        node.setCategory(skill.getCategory());
        node.setLevel(skill.getLevel());
        node.setParentId(skill.getParentId());
        node.setSortOrder(skill.getSortOrder());
        node.setPrerequisiteSkills(skill.getPrerequisiteSkills());
        return node;
    }
}
