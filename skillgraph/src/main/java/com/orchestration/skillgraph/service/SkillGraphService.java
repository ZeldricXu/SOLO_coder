package com.orchestration.skillgraph.service;

import com.orchestration.persistence.entity.*;
import java.util.List;
import java.util.Map;

public interface SkillGraphService {

    Long createCategory(SkillCategory category);

    List<SkillCategory> listCategories();

    Long createSkill(SkillDefinition skill);

    SkillDefinition getSkill(Long id);

    List<SkillDefinition> listSkills(Long categoryId, Integer page, Integer size);

    boolean addSkillRelation(SkillRelation relation);

    Long createLearningPath(LearningPath path);

    List<LearningPath> listLearningPaths();

    boolean evaluateEmployeeSkill(EmployeeSkill employeeSkill);

    List<EmployeeSkill> getEmployeeSkills(Long employeeId);

    List<Map<String, Object>> recommendLearningPath(Long employeeId, Long targetSkillId);

    Map<String, Object> getSkillTree();

    Map<String, Object> getEmployeeSkillGap(Long employeeId, Long targetRoleId);
}
