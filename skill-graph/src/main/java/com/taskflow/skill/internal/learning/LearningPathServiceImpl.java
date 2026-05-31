package com.taskflow.skill.internal.learning;

import com.taskflow.common.utils.IdGenerator;
import com.taskflow.data.entity.EmployeeSkillEntity;
import com.taskflow.data.entity.SkillEntity;
import com.taskflow.data.mapper.EmployeeSkillMapper;
import com.taskflow.data.mapper.SkillMapper;
import com.taskflow.skill.api.LearningPathService;
import com.taskflow.skill.api.SkillTreeService;
import com.taskflow.skill.domain.LearningPath;
import com.taskflow.skill.domain.LearningStep;
import com.taskflow.skill.domain.SkillNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 学习路径服务实现
 * 内部实现，不对外暴露
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LearningPathServiceImpl implements LearningPathService {

    private final SkillTreeService skillTreeService;
    private final SkillMapper skillMapper;
    private final EmployeeSkillMapper employeeSkillMapper;

    @Override
    public Mono<LearningPath> generateLearningPath(String tenantId, String employeeId,
                                                   String targetSkillId, Integer targetLevel) {
        return skillTreeService.getSkill(tenantId, targetSkillId)
                .map(targetSkill -> {
                    List<String> skillPath = new ArrayList<>();
                    collectSkillPath(targetSkill, skillPath);
                    Collections.reverse(skillPath);

                    Map<String, Integer> currentProficiencies = getEmployeeProficiencies(tenantId, employeeId);

                    List<LearningStep> steps = new ArrayList<>();
                    int totalHours = 0;

                    for (String skillId : skillPath) {
                        SkillEntity skill = skillMapper.selectOne(
                                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.<SkillEntity>lambdaQuery()
                                        .eq(SkillEntity::getTenantId, tenantId)
                                        .eq(SkillEntity::getSkillId, skillId)
                        );
                        if (skill == null) continue;

                        int currentLevel = currentProficiencies.getOrDefault(skillId, 0);
                        int stepTargetLevel = skillId.equals(targetSkillId) ? targetLevel : Math.min(3, skill.getLevel());

                        if (currentLevel >= stepTargetLevel) {
                            continue;
                        }

                        LearningStep step = createLearningStep(skill, currentLevel, stepTargetLevel);
                        steps.add(step);
                        totalHours += step.getEstimatedHours();
                    }

                    return LearningPath.builder()
                            .pathId(IdGenerator.generateId("path"))
                            .employeeId(employeeId)
                            .targetSkillId(targetSkillId)
                            .targetSkillName(targetSkill.getName())
                            .targetLevel(targetLevel)
                            .steps(steps)
                            .estimatedHours(totalHours)
                            .estimatedDays((int) Math.ceil(totalHours / 8.0))
                            .recommendations(buildRecommendations(steps))
                            .build();
                });
    }

    private void collectSkillPath(SkillNode node, List<String> path) {
        path.add(node.getSkillId());
    }

    private Map<String, Integer> getEmployeeProficiencies(String tenantId, String employeeId) {
        List<EmployeeSkillEntity> skills = employeeSkillMapper.selectByEmployeeId(tenantId, employeeId);
        return skills.stream()
                .collect(Collectors.toMap(
                        EmployeeSkillEntity::getSkillId,
                        EmployeeSkillEntity::getProficiencyLevel
                ));
    }

    private LearningStep createLearningStep(SkillEntity skill, int currentLevel, int targetLevel) {
        int levelGap = targetLevel - currentLevel;
        int estimatedHours = levelGap * 20 * (skill.getLevel() + 1);

        List<String> learningResources = new ArrayList<>();
        learningResources.add("官方文档 - " + skill.getName());
        learningResources.add(skill.getName() + " 入门教程");
        if (targetLevel >= 3) {
            learningResources.add(skill.getName() + " 高级实践");
        }
        if (targetLevel >= 4) {
            learningResources.add(skill.getName() + " 源码分析");
        }

        List<String> assessmentMethods = new ArrayList<>();
        assessmentMethods.add("在线测验");
        if (targetLevel >= 3) {
            assessmentMethods.add("项目实践评估");
        }
        if (targetLevel >= 4) {
            assessmentMethods.add("技术面试");
        }

        String difficulty;
        if (levelGap <= 1) {
            difficulty = "easy";
        } else if (levelGap <= 2) {
            difficulty = "medium";
        } else {
            difficulty = "hard";
        }

        return LearningStep.builder()
                .stepId(IdGenerator.generateId("step"))
                .skillId(skill.getSkillId())
                .skillName(skill.getName())
                .currentLevel(currentLevel)
                .targetLevel(targetLevel)
                .difficulty(difficulty)
                .estimatedHours(estimatedHours)
                .prerequisites(Collections.emptyList())
                .learningResources(learningResources)
                .assessmentMethods(assessmentMethods)
                .build();
    }

    private Map<String, Object> buildRecommendations(List<LearningStep> steps) {
        Map<String, Object> recommendations = new HashMap<>();

        recommendations.put("learningStyle", "建议每天学习2-3小时，保持持续性");
        recommendations.put("focusAreas", steps.stream()
                .filter(s -> "hard".equals(s.getDifficulty()))
                .map(LearningStep::getSkillName)
                .collect(Collectors.toList()));

        if (!steps.isEmpty()) {
            recommendations.put("firstStep", "建议先从 " + steps.get(0).getSkillName() + " 开始学习");
        }

        return recommendations;
    }

    @Override
    public Mono<List<Map<String, Object>>> getRecommendedSkills(String tenantId, String employeeId) {
        return skillTreeService.getSkillTree(tenantId)
                .map(skillTree -> {
                    Map<String, Integer> proficiencies = getEmployeeProficiencies(tenantId, employeeId);
                    List<Map<String, Object>> recommendations = new ArrayList<>();
                    collectRecommendations(skillTree, proficiencies, recommendations, 0);

                    return recommendations.stream()
                            .sorted((a, b) -> Integer.compare(
                                    (int) b.get("priorityScore"),
                                    (int) a.get("priorityScore")
                            ))
                            .limit(5)
                            .collect(Collectors.toList());
                });
    }

    private void collectRecommendations(SkillNode node, Map<String, Integer> proficiencies,
                                        List<Map<String, Object>> recommendations, int depth) {
        if (depth > 0) {
            int currentLevel = proficiencies.getOrDefault(node.getSkillId(), 0);
            if (currentLevel < 3) {
                Map<String, Object> rec = new HashMap<>();
                rec.put("skillId", node.getSkillId());
                rec.put("skillName", node.getName());
                rec.put("currentLevel", currentLevel);
                rec.put("targetLevel", 3);
                rec.put("category", node.getCategory());

                int priorityScore = (3 - currentLevel) * (5 - depth);
                rec.put("priorityScore", priorityScore);

                recommendations.add(rec);
            }
        }

        for (SkillNode child : node.getChildren()) {
            collectRecommendations(child, proficiencies, recommendations, depth + 1);
        }
    }
}
