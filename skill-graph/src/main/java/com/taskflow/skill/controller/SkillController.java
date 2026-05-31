package com.taskflow.skill.controller;

import com.taskflow.common.model.Result;
import com.taskflow.skill.api.LearningPathService;
import com.taskflow.skill.api.SkillAssessmentService;
import com.taskflow.skill.api.SkillTreeService;
import com.taskflow.skill.domain.EmployeeProfile;
import com.taskflow.skill.domain.LearningPath;
import com.taskflow.skill.domain.Skill;
import com.taskflow.skill.domain.SkillAssessment;
import com.taskflow.skill.domain.SkillNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 技能控制器
 * 仅依赖接口，不依赖具体实现
 */
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillTreeService skillTreeService;
    private final SkillAssessmentService skillAssessmentService;
    private final LearningPathService learningPathService;

    @GetMapping("/tree")
    public Mono<Result<SkillNode>> getSkillTree(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return skillTreeService.getSkillTree(tenantId)
                .map(Result::success);
    }

    @GetMapping("/{skillId}")
    public Mono<Result<SkillNode>> getSkill(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String skillId) {
        return skillTreeService.getSkill(tenantId, skillId)
                .map(Result::success);
    }

    @PostMapping
    public Mono<Result<Skill>> createSkill(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody Skill skill) {
        return skillTreeService.createSkill(tenantId, skill)
                .map(Result::success);
    }

    @DeleteMapping("/{skillId}")
    public Mono<Result<Void>> deleteSkill(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String skillId) {
        return skillTreeService.deleteSkill(tenantId, skillId)
                .then(Mono.just(Result.success(null)));
    }

    @PostMapping("/assess")
    public Mono<Result<SkillAssessment>> assessSkill(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody SkillAssessment assessment) {
        return skillAssessmentService.assessSkill(tenantId, assessment)
                .map(Result::success);
    }

    @GetMapping("/employee/{employeeId}")
    public Mono<Result<EmployeeProfile>> getEmployeeProfile(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String employeeId) {
        return skillAssessmentService.getEmployeeProfile(tenantId, employeeId)
                .map(Result::success);
    }

    @GetMapping("/employee/{employeeId}/assessments")
    public Mono<Result<List<SkillAssessment>>> getEmployeeAssessments(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String employeeId) {
        return skillAssessmentService.getEmployeeAssessments(tenantId, employeeId)
                .map(Result::success);
    }

    @PostMapping("/team/matrix")
    public Mono<Result<Map<String, Object>>> getTeamSkillMatrix(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody List<String> employeeIds) {
        return skillAssessmentService.getTeamSkillMatrix(tenantId, employeeIds)
                .map(Result::success);
    }

    @PostMapping("/learning-path")
    public Mono<Result<LearningPath>> generateLearningPath(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody Map<String, Object> request) {
        return learningPathService.generateLearningPath(
                tenantId,
                (String) request.get("employeeId"),
                (String) request.get("skillId"),
                (Integer) request.getOrDefault("targetLevel", 3)
        ).map(Result::success);
    }

    @GetMapping("/employee/{employeeId}/recommendations")
    public Mono<Result<List<Map<String, Object>>>> getRecommendedSkills(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String employeeId) {
        return learningPathService.getRecommendedSkills(tenantId, employeeId)
                .map(Result::success);
    }
}
