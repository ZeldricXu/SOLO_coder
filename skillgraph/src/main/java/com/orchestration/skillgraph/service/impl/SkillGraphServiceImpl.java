package com.orchestration.skillgraph.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.orchestration.common.exception.BusinessException;
import com.orchestration.common.util.JsonUtil;
import com.orchestration.persistence.entity.*;
import com.orchestration.persistence.mapper.*;
import com.orchestration.skillgraph.service.SkillGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SkillGraphServiceImpl implements SkillGraphService {

    private final SkillCategoryMapper categoryMapper;
    private final SkillDefinitionMapper skillMapper;
    private final SkillRelationMapper relationMapper;
    private final EmployeeSkillMapper employeeSkillMapper;
    private final LearningPathMapper learningPathMapper;

    @Override
    public Long createCategory(SkillCategory category) {
        categoryMapper.insert(category);
        return category.getId();
    }

    @Override
    public List<SkillCategory> listCategories() {
        return categoryMapper.selectList(
                new LambdaQueryWrapper<SkillCategory>().orderByAsc(SkillCategory::getSortOrder)
        );
    }

    @Override
    public Long createSkill(SkillDefinition skill) {
        skillMapper.insert(skill);
        return skill.getId();
    }

    @Override
    public SkillDefinition getSkill(Long id) {
        return skillMapper.selectById(id);
    }

    @Override
    public List<SkillDefinition> listSkills(Long categoryId, Integer page, Integer size) {
        Page<SkillDefinition> pageResult = skillMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<SkillDefinition>()
                        .eq(categoryId != null, SkillDefinition::getCategoryId, categoryId)
                        .orderByDesc(SkillDefinition::getCreatedAt)
        );
        return pageResult.getRecords();
    }

    @Override
    public boolean addSkillRelation(SkillRelation relation) {
        return relationMapper.insert(relation) > 0;
    }

    @Override
    public Long createLearningPath(LearningPath path) {
        learningPathMapper.insert(path);
        return path.getId();
    }

    @Override
    public List<LearningPath> listLearningPaths() {
        return learningPathMapper.selectList(
                new LambdaQueryWrapper<LearningPath>().eq(LearningPath::getEnabled, 1)
        );
    }

    @Override
    public boolean evaluateEmployeeSkill(EmployeeSkill employeeSkill) {
        EmployeeSkill existing = employeeSkillMapper.selectOne(
                new LambdaQueryWrapper<EmployeeSkill>()
                        .eq(EmployeeSkill::getEmployeeId, employeeSkill.getEmployeeId())
                        .eq(EmployeeSkill::getSkillId, employeeSkill.getSkillId())
        );

        employeeSkill.setLastEvaluatedAt(LocalDateTime.now());
        if (existing != null) {
            employeeSkill.setId(existing.getId());
            return employeeSkillMapper.updateById(employeeSkill) > 0;
        } else {
            employeeSkill.setLearningProgress(new BigDecimal("1.0"));
            return employeeSkillMapper.insert(employeeSkill) > 0;
        }
    }

    @Override
    public List<EmployeeSkill> getEmployeeSkills(Long employeeId) {
        return employeeSkillMapper.selectList(
                new LambdaQueryWrapper<EmployeeSkill>().eq(EmployeeSkill::getEmployeeId, employeeId)
        );
    }

    @Override
    public List<Map<String, Object>> recommendLearningPath(Long employeeId, Long targetSkillId) {
        SkillDefinition targetSkill = skillMapper.selectById(targetSkillId);
        if (targetSkill == null) {
            throw new BusinessException("目标技能不存在");
        }

        Set<Long> learnedSkills = getEmployeeSkills(employeeId).stream()
                .filter(es -> es.getProficiencyLevel() >= 3)
                .map(EmployeeSkill::getSkillId)
                .collect(Collectors.toSet());

        List<Long> learningSequence = new ArrayList<>();
        buildLearningPath(targetSkillId, learnedSkills, learningSequence, new HashSet<>());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Long skillId : learningSequence) {
            SkillDefinition skill = skillMapper.selectById(skillId);
            if (skill != null) {
                Map<String, Object> item = new HashMap<>();
                item.put("skillId", skill.getId());
                item.put("skillName", skill.getSkillName());
                item.put("skillCode", skill.getSkillCode());
                item.put("skillLevel", skill.getSkillLevel());
                item.put("learned", learnedSkills.contains(skillId));
                result.add(item);
            }
        }
        return result;
    }

    private void buildLearningPath(Long skillId, Set<Long> learnedSkills, List<Long> sequence, Set<Long> visited) {
        if (visited.contains(skillId)) {
            return;
        }
        visited.add(skillId);

        List<SkillRelation> prerequisites = relationMapper.selectList(
                new LambdaQueryWrapper<SkillRelation>().eq(SkillRelation::getSkillId, skillId)
        );

        for (SkillRelation pre : prerequisites) {
            buildLearningPath(pre.getPrerequisiteSkillId(), learnedSkills, sequence, visited);
        }

        if (!learnedSkills.contains(skillId)) {
            sequence.add(skillId);
        }
    }

    @Override
    public Map<String, Object> getSkillTree() {
        List<SkillCategory> categories = listCategories();
        List<SkillDefinition> skills = skillMapper.selectList(null);

        Map<Long, List<SkillDefinition>> skillByCategory = skills.stream()
                .collect(Collectors.groupingBy(SkillDefinition::getCategoryId));

        List<Map<String, Object>> tree = new ArrayList<>();
        for (SkillCategory category : categories) {
            Map<String, Object> node = new HashMap<>();
            node.put("id", category.getId());
            node.put("name", category.getCategoryName());
            node.put("code", category.getCategoryCode());
            node.put("type", "category");

            List<Map<String, Object>> children = new ArrayList<>();
            List<SkillDefinition> categorySkills = skillByCategory.getOrDefault(category.getId(), Collections.emptyList());
            for (SkillDefinition skill : categorySkills) {
                Map<String, Object> skillNode = new HashMap<>();
                skillNode.put("id", skill.getId());
                skillNode.put("name", skill.getSkillName());
                skillNode.put("code", skill.getSkillCode());
                skillNode.put("type", "skill");
                skillNode.put("level", skill.getSkillLevel());
                children.add(skillNode);
            }
            node.put("children", children);
            tree.add(node);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("tree", tree);
        result.put("totalCategories", categories.size());
        result.put("totalSkills", skills.size());
        return result;
    }

    @Override
    public Map<String, Object> getEmployeeSkillGap(Long employeeId, Long targetRoleId) {
        List<EmployeeSkill> employeeSkills = getEmployeeSkills(employeeId);
        Map<Long, Integer> skillLevels = employeeSkills.stream()
                .collect(Collectors.toMap(EmployeeSkill::getSkillId, EmployeeSkill::getProficiencyLevel));

        List<Map<String, Object>> gaps = new ArrayList<>();
        List<SkillDefinition> allSkills = skillMapper.selectList(null);

        for (SkillDefinition skill : allSkills) {
            Integer currentLevel = skillLevels.getOrDefault(skill.getId(), 0);
            int requiredLevel = 4;

            if (currentLevel < requiredLevel) {
                Map<String, Object> gap = new HashMap<>();
                gap.put("skillId", skill.getId());
                gap.put("skillName", skill.getSkillName());
                gap.put("currentLevel", currentLevel);
                gap.put("requiredLevel", requiredLevel);
                gap.put("gap", requiredLevel - currentLevel);
                gaps.add(gap);
            }
        }

        gaps.sort((a, b) -> Integer.compare((Integer) b.get("gap"), (Integer) a.get("gap")));

        Map<String, Object> result = new HashMap<>();
        result.put("employeeId", employeeId);
        result.put("targetRoleId", targetRoleId);
        result.put("gaps", gaps);
        result.put("totalGaps", gaps.size());
        result.put("masteredSkills", skillLevels.size());
        return result;
    }
}
