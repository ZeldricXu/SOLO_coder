package com.smartflow.skillgraph.service;

import com.smartflow.common.exception.BusinessException;
import com.smartflow.common.utils.IdGenerator;
import com.smartflow.persistence.entity.Employee;
import com.smartflow.persistence.entity.EmployeeSkill;
import com.smartflow.persistence.entity.Skill;
import com.smartflow.persistence.mapper.EmployeeMapper;
import com.smartflow.persistence.mapper.EmployeeSkillMapper;
import com.smartflow.persistence.mapper.SkillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SkillGraphService {

    private final SkillMapper skillMapper;
    private final EmployeeSkillMapper employeeSkillMapper;
    private final EmployeeMapper employeeMapper;

    @Transactional
    public Skill createSkill(Skill skill) {
        Skill existing = skillMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Skill>()
                .eq(Skill::getSkillCode, skill.getSkillCode())
        );
        if (existing != null) {
            throw new BusinessException("技能编码已存在");
        }

        skill.setId(IdGenerator.generateId());
        skill.setEnabled(1);

        if (skill.getParentId() != null) {
            Skill parent = skillMapper.selectById(skill.getParentId());
            if (parent == null) {
                throw new BusinessException("父技能不存在");
            }
            skill.setLevel(parent.getLevel() + 1);
            skill.setParentPath(parent.getParentPath() + "/" + skill.getSkillCode());
        } else {
            skill.setLevel(1);
            skill.setParentPath("/" + skill.getSkillCode());
        }

        skillMapper.insert(skill);
        return skill;
    }

    public Skill getSkill(Long skillId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw new BusinessException("技能不存在");
        }
        return skill;
    }

    public List<Skill> getSkillTree() {
        List<Skill> allSkills = skillMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Skill>()
                .eq(Skill::getEnabled, 1)
                .orderByAsc(Skill::getLevel, Skill::getSortOrder)
        );
        return buildTree(allSkills, null);
    }

    private List<Skill> buildTree(List<Skill> allSkills, Long parentId) {
        List<Skill> children = allSkills.stream()
            .filter(s -> (parentId == null && s.getParentId() == null) 
                    || (parentId != null && parentId.equals(s.getParentId())))
            .collect(Collectors.toList());

        for (Skill child : children) {
            child.setChildren(buildTree(allSkills, child.getId()));
        }

        return children;
    }

    @Transactional
    public boolean deleteSkill(Long skillId) {
        Skill skill = getSkill(skillId);
        Long count = skillMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Skill>()
                .eq(Skill::getParentId, skillId)
        );
        if (count > 0) {
            throw new BusinessException("存在子技能，无法删除");
        }
        skill.setDeleted(1);
        skillMapper.updateById(skill);
        return true;
    }

    @Transactional
    public EmployeeSkill setEmployeeSkill(EmployeeSkill employeeSkill) {
        Employee employee = employeeMapper.selectById(employeeSkill.getEmployeeId());
        if (employee == null) {
            throw new BusinessException("员工不存在");
        }
        Skill skill = skillMapper.selectById(employeeSkill.getSkillId());
        if (skill == null) {
            throw new BusinessException("技能不存在");
        }

        EmployeeSkill existing = employeeSkillMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EmployeeSkill>()
                .eq(EmployeeSkill::getEmployeeId, employeeSkill.getEmployeeId())
                .eq(EmployeeSkill::getSkillId, employeeSkill.getSkillId())
        );

        employeeSkill.setEmployeeName(employee.getName());
        employeeSkill.setSkillName(skill.getSkillName());

        if (existing != null) {
            existing.setProficiency(employeeSkill.getProficiency());
            existing.setExperienceYears(employeeSkill.getExperienceYears());
            existing.setCertificationLevel(employeeSkill.getCertificationLevel());
            existing.setEvaluationScore(employeeSkill.getEvaluationScore());
            employeeSkillMapper.updateById(existing);
            return existing;
        } else {
            employeeSkill.setId(IdGenerator.generateId());
            employeeSkillMapper.insert(employeeSkill);
            return employeeSkill;
        }
    }

    public List<EmployeeSkill> getEmployeeSkills(Long employeeId) {
        return employeeSkillMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EmployeeSkill>()
                .eq(EmployeeSkill::getEmployeeId, employeeId)
        );
    }

    public Map<String, Object> evaluateEmployeeSkills(Long employeeId) {
        List<EmployeeSkill> skills = getEmployeeSkills(employeeId);
        Employee employee = employeeMapper.selectById(employeeId);

        Map<String, Object> result = new HashMap<>();
        result.put("employeeId", employeeId);
        result.put("employeeName", employee != null ? employee.getName() : null);

        if (skills.isEmpty()) {
            result.put("totalSkills", 0);
            result.put("avgProficiency", 0);
            result.put("skillLevel", "BEGINNER");
            result.put("skills", Collections.emptyList());
            return result;
        }

        double avgProficiency = skills.stream()
            .mapToInt(s -> s.getProficiency() != null ? s.getProficiency() : 0)
            .average()
            .orElse(0);

        String skillLevel;
        if (avgProficiency >= 4.5) {
            skillLevel = "EXPERT";
        } else if (avgProficiency >= 3.5) {
            skillLevel = "ADVANCED";
        } else if (avgProficiency >= 2.5) {
            skillLevel = "INTERMEDIATE";
        } else {
            skillLevel = "BEGINNER";
        }

        Map<String, List<EmployeeSkill>> skillsByCategory = new HashMap<>();
        for (EmployeeSkill es : skills) {
            Skill skill = skillMapper.selectById(es.getSkillId());
            if (skill != null) {
                skillsByCategory.computeIfAbsent(skill.getCategory(), k -> new ArrayList<>()).add(es);
            }
        }

        result.put("totalSkills", skills.size());
        result.put("avgProficiency", String.format("%.2f", avgProficiency));
        result.put("skillLevel", skillLevel);
        result.put("skills", skills);
        result.put("skillsByCategory", skillsByCategory);

        return result;
    }

    public List<Map<String, Object>> recommendLearningPath(Long employeeId, String targetRole) {
        List<EmployeeSkill> currentSkills = getEmployeeSkills(employeeId);
        Set<Long> currentSkillIds = currentSkills.stream()
            .map(EmployeeSkill::getSkillId)
            .collect(Collectors.toSet());

        Map<String, List<String>> requiredSkillsMap = getRequiredSkillsByRole();
        List<String> requiredSkillCodes = requiredSkillsMap.getOrDefault(targetRole, Collections.emptyList());

        List<Skill> allSkills = skillMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Skill>()
                .in(Skill::getSkillCode, requiredSkillCodes)
        );

        List<Map<String, Object>> learningPath = new ArrayList<>();
        for (Skill skill : allSkills) {
            if (!currentSkillIds.contains(skill.getId())) {
                Map<String, Object> pathItem = new HashMap<>();
                pathItem.put("skillId", skill.getId());
                pathItem.put("skillName", skill.getSkillName());
                pathItem.put("skillCode", skill.getSkillCode());
                pathItem.put("category", skill.getCategory());
                pathItem.put("level", skill.getLevel());
                pathItem.put("priority", calculatePriority(skill, currentSkills));
                pathItem.put("estimatedHours", estimateLearningHours(skill));
                pathItem.put("resources", getLearningResources(skill));
                learningPath.add(pathItem);
            }
        }

        learningPath.sort(Comparator.comparingInt(m -> (Integer) m.get("priority")));
        return learningPath;
    }

    private Map<String, List<String>> getRequiredSkillsByRole() {
        Map<String, List<String>> roleSkills = new HashMap<>();
        roleSkills.put("BACKEND_DEVELOPER", Arrays.asList("JAVA", "SPRING", "MYSQL", "REDIS", "MQ"));
        roleSkills.put("FRONTEND_DEVELOPER", Arrays.asList("JAVASCRIPT", "VUE", "REACT", "CSS", "HTML"));
        roleSkills.put("DEVOPS_ENGINEER", Arrays.asList("LINUX", "DOCKER", "K8S", "CI_CD", "MONITORING"));
        roleSkills.put("DATA_ANALYST", Arrays.asList("SQL", "PYTHON", "STATISTICS", "TABLEAU", "ETL"));
        return roleSkills;
    }

    private int calculatePriority(Skill skill, List<EmployeeSkill> currentSkills) {
        return skill.getLevel();
    }

    private int estimateLearningHours(Skill skill) {
        switch (skill.getLevel()) {
            case 1: return 8;
            case 2: return 24;
            case 3: return 40;
            default: return 16;
        }
    }

    private List<String> getLearningResources(Skill skill) {
        List<String> resources = new ArrayList<>();
        resources.add("官方文档: " + skill.getSkillName());
        resources.add("在线课程: " + skill.getSkillName() + " 入门到精通");
        resources.add("实战项目: " + skill.getSkillName() + " 项目实战");
        return resources;
    }

    public List<Map<String, Object>> findEmployeesBySkills(List<String> requiredSkills, Integer minProficiency) {
        List<EmployeeSkill> employeeSkills = employeeSkillMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EmployeeSkill>()
                .ge(EmployeeSkill::getProficiency, minProficiency != null ? minProficiency : 3)
        );

        Map<Long, List<EmployeeSkill>> skillsByEmployee = employeeSkills.stream()
            .collect(Collectors.groupingBy(EmployeeSkill::getEmployeeId));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, List<EmployeeSkill>> entry : skillsByEmployee.entrySet()) {
            Set<String> employeeSkillNames = entry.getValue().stream()
                .map(EmployeeSkill::getSkillName)
                .collect(Collectors.toSet());

            boolean hasAllSkills = employeeSkillNames.containsAll(requiredSkills);
            if (hasAllSkills) {
                Employee employee = employeeMapper.selectById(entry.getKey());
                Map<String, Object> empMap = new HashMap<>();
                empMap.put("employee", employee);
                empMap.put("skills", entry.getValue());
                empMap.put("matchCount", requiredSkills.size());
                result.add(empMap);
            }
        }

        return result;
    }
}
