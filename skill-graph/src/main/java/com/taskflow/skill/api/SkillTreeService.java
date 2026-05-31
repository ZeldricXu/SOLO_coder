package com.taskflow.skill.api;

import com.taskflow.skill.domain.Skill;
import com.taskflow.skill.domain.SkillNode;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 技能树服务 - 最小化接口
 * 仅定义技能树相关的核心操作
 */
public interface SkillTreeService {

    /**
     * 获取完整技能树
     * @param tenantId 租户ID
     * @return 技能树根节点
     */
    Mono<SkillNode> getSkillTree(String tenantId);

    /**
     * 获取指定技能及其子技能
     * @param tenantId 租户ID
     * @param skillId 技能ID
     * @return 技能节点
     */
    Mono<SkillNode> getSkill(String tenantId, String skillId);

    /**
     * 创建技能
     * @param tenantId 租户ID
     * @param skill 技能定义
     * @return 创建后的技能
     */
    Mono<Skill> createSkill(String tenantId, Skill skill);

    /**
     * 删除技能
     * @param tenantId 租户ID
     * @param skillId 技能ID
     */
    Mono<Void> deleteSkill(String tenantId, String skillId);

    /**
     * 获取指定分类的技能列表
     * @param tenantId 租户ID
     * @param category 分类
     * @return 技能列表
     */
    Mono<List<Skill>> getSkillsByCategory(String tenantId, String category);
}
