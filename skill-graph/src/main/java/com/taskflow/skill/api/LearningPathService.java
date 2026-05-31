package com.taskflow.skill.api;

import com.taskflow.skill.domain.LearningPath;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 学习路径服务 - 最小化接口
 * 仅定义学习路径相关的核心操作
 */
public interface LearningPathService {

    /**
     * 生成学习路径
     * @param tenantId 租户ID
     * @param employeeId 员工ID
     * @param targetSkillId 目标技能ID
     * @param targetLevel 目标等级
     * @return 学习路径
     */
    Mono<LearningPath> generateLearningPath(String tenantId, String employeeId, String targetSkillId, Integer targetLevel);

    /**
     * 获取推荐学习的技能
     * @param tenantId 租户ID
     * @param employeeId 员工ID
     * @return 推荐技能列表
     */
    Mono<List<Map<String, Object>>> getRecommendedSkills(String tenantId, String employeeId);
}
