package com.taskflow.skill.api;

import com.taskflow.skill.domain.EmployeeProfile;
import com.taskflow.skill.domain.SkillAssessment;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 技能评估服务 - 最小化接口
 * 仅定义技能评估相关的核心操作
 */
public interface SkillAssessmentService {

    /**
     * 评估员工技能
     * @param tenantId 租户ID
     * @param assessment 评估结果
     * @return 保存后的评估结果
     */
    Mono<SkillAssessment> assessSkill(String tenantId, SkillAssessment assessment);

    /**
     * 获取员工的所有技能评估
     * @param tenantId 租户ID
     * @param employeeId 员工ID
     * @return 技能评估列表
     */
    Mono<List<SkillAssessment>> getEmployeeAssessments(String tenantId, String employeeId);

    /**
     * 获取员工技能档案
     * @param tenantId 租户ID
     * @param employeeId 员工ID
     * @return 员工技能档案
     */
    Mono<EmployeeProfile> getEmployeeProfile(String tenantId, String employeeId);

    /**
     * 获取团队技能矩阵
     * @param tenantId 租户ID
     * @param employeeIds 员工ID列表
     * @return 团队技能矩阵
     */
    Mono<Map<String, Object>> getTeamSkillMatrix(String tenantId, List<String> employeeIds);
}
