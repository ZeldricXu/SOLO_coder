package com.taskflow.skill.internal.assessment;

import com.taskflow.data.entity.EmployeeSkillEntity;
import com.taskflow.data.entity.SkillEntity;
import com.taskflow.data.mapper.EmployeeSkillMapper;
import com.taskflow.data.mapper.SkillMapper;
import com.taskflow.skill.api.SkillAssessmentService;
import com.taskflow.skill.domain.EmployeeProfile;
import com.taskflow.skill.domain.SkillAssessment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 技能评估服务实现
 * 内部实现，不对外暴露
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillAssessmentServiceImpl implements SkillAssessmentService {

    private final EmployeeSkillMapper employeeSkillMapper;
    private final SkillMapper skillMapper;

    @Override
    public Mono<SkillAssessment> assessSkill(String tenantId, SkillAssessment assessment) {
        return Mono.fromCallable(() -> {
            EmployeeSkillEntity existing = employeeSkillMapper.selectByEmployeeAndSkill(
                    tenantId, assessment.getEmployeeId(), assessment.getSkillId());

            EmployeeSkillEntity entity = new EmployeeSkillEntity();
            entity.setTenantId(tenantId);
            entity.setEmployeeId(assessment.getEmployeeId());
            entity.setSkillId(assessment.getSkillId());
            entity.setProficiencyLevel(assessment.getProficiencyLevel());
            entity.setAssessmentDate(LocalDateTime.now());
            entity.setAssessor(assessment.getAssessor());
            entity.setNotes(assessment.getNotes());
            entity.setEvidence(assessment.getEvidence());

            if (existing == null) {
                employeeSkillMapper.insert(entity);
            } else {
                entity.setId(existing.getId());
                employeeSkillMapper.updateById(entity);
            }

            log.info("Skill assessment completed: employee={}, skill={}, level={}",
                    assessment.getEmployeeId(), assessment.getSkillId(), assessment.getProficiencyLevel());

            assessment.setAssessmentDate(entity.getAssessmentDate());
            assessment.setAssessmentStatus("completed");
            return assessment;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<SkillAssessment>> getEmployeeAssessments(String tenantId, String employeeId) {
        return Mono.fromCallable(() -> {
            List<EmployeeSkillEntity> entities = employeeSkillMapper.selectByEmployeeId(tenantId, employeeId);

            Map<String, SkillEntity> skillMap = new HashMap<>();
            for (EmployeeSkillEntity entity : entities) {
                SkillEntity skill = skillMapper.selectOne(
                        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.<SkillEntity>lambdaQuery()
                                .eq(SkillEntity::getTenantId, tenantId)
                                .eq(SkillEntity::getSkillId, entity.getSkillId())
                );
                if (skill != null) {
                    skillMap.put(entity.getSkillId(), skill);
                }
            }

            return entities.stream()
                    .map(entity -> {
                        SkillAssessment assessment = SkillAssessment.builder()
                                .employeeId(entity.getEmployeeId())
                                .skillId(entity.getSkillId())
                                .proficiencyLevel(entity.getProficiencyLevel())
                                .assessmentDate(entity.getAssessmentDate())
                                .assessor(entity.getAssessor())
                                .notes(entity.getNotes())
                                .evidence(entity.getEvidence())
                                .build();

                        SkillEntity skill = skillMap.get(entity.getSkillId());
                        if (skill != null) {
                            assessment.setSkillName(skill.getName());
                        }

                        return assessment;
                    })
                    .collect(Collectors.toList());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<EmployeeProfile> getEmployeeProfile(String tenantId, String employeeId) {
        return getEmployeeAssessments(tenantId, employeeId)
                .map(assessments -> {
                    Map<String, Integer> proficiencies = new HashMap<>();
                    List<String> strongSkills = new ArrayList<>();
                    List<String> weakSkills = new ArrayList<>();
                    Map<String, List<Integer>> categoryScores = new HashMap<>();
                    int certifiedCount = 0;

                    for (SkillAssessment assessment : assessments) {
                        String skillName = assessment.getSkillName() != null ? assessment.getSkillName() : assessment.getSkillId();
                        proficiencies.put(assessment.getSkillId(), assessment.getProficiencyLevel());

                        if (assessment.getProficiencyLevel() >= 4) {
                            strongSkills.add(skillName);
                            certifiedCount++;
                        } else if (assessment.getProficiencyLevel() <= 1) {
                            weakSkills.add(skillName);
                        }

                        SkillEntity skill = skillMapper.selectOne(
                                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.<SkillEntity>lambdaQuery()
                                        .eq(SkillEntity::getTenantId, tenantId)
                                        .eq(SkillEntity::getSkillId, assessment.getSkillId())
                        );
                        if (skill != null && skill.getCategory() != null) {
                            categoryScores.computeIfAbsent(skill.getCategory(), k -> new ArrayList<>())
                                    .add(assessment.getProficiencyLevel());
                        }
                    }

                    Map<String, Double> categoryAvgScores = new HashMap<>();
                    for (Map.Entry<String, List<Integer>> entry : categoryScores.entrySet()) {
                        double avg = entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0);
                        categoryAvgScores.put(entry.getKey(), Math.round(avg * 10) / 10.0);
                    }

                    double overallScore = proficiencies.values().stream()
                            .mapToInt(Integer::intValue)
                            .average()
                            .orElse(0);

                    return EmployeeProfile.builder()
                            .employeeId(employeeId)
                            .skillProficiencies(proficiencies)
                            .strongSkills(strongSkills)
                            .weakSkills(weakSkills)
                            .categoryScores(categoryAvgScores)
                            .overallScore(Math.round(overallScore * 10) / 10.0)
                            .totalSkills(proficiencies.size())
                            .certifiedSkills(certifiedCount)
                            .recommendedSkills(new ArrayList<>())
                            .build();
                });
    }

    @Override
    public Mono<Map<String, Object>> getTeamSkillMatrix(String tenantId, List<String> employeeIds) {
        return Mono.fromCallable(() -> {
            Map<String, Object> matrix = new HashMap<>();
            List<Map<String, Object>> employeeRows = new ArrayList<>();

            for (String employeeId : employeeIds) {
                EmployeeProfile profile = getEmployeeProfile(tenantId, employeeId).block();
                if (profile != null) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("employeeId", employeeId);
                    row.put("overallScore", profile.getOverallScore());
                    row.put("skills", profile.getSkillProficiencies());
                    employeeRows.add(row);
                }
            }

            matrix.put("employees", employeeRows);
            matrix.put("totalEmployees", employeeIds.size());
            return matrix;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
