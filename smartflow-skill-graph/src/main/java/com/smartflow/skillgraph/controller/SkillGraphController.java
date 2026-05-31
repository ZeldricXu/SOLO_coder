package com.smartflow.skillgraph.controller;

import com.smartflow.common.base.Result;
import com.smartflow.persistence.entity.EmployeeSkill;
import com.smartflow.persistence.entity.Skill;
import com.smartflow.skillgraph.service.SkillGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/skill")
@RequiredArgsConstructor
public class SkillGraphController {

    private final SkillGraphService skillGraphService;

    @PostMapping
    public Result<Skill> createSkill(@RequestBody Skill skill) {
        Skill created = skillGraphService.createSkill(skill);
        return Result.success(created);
    }

    @GetMapping("/{skillId}")
    public Result<Skill> getSkill(@PathVariable Long skillId) {
        Skill skill = skillGraphService.getSkill(skillId);
        return Result.success(skill);
    }

    @GetMapping("/tree")
    public Result<List<Skill>> getSkillTree() {
        List<Skill> tree = skillGraphService.getSkillTree();
        return Result.success(tree);
    }

    @DeleteMapping("/{skillId}")
    public Result<Boolean> deleteSkill(@PathVariable Long skillId) {
        boolean success = skillGraphService.deleteSkill(skillId);
        return Result.success(success);
    }

    @PostMapping("/employee")
    public Result<EmployeeSkill> setEmployeeSkill(@RequestBody EmployeeSkill employeeSkill) {
        EmployeeSkill result = skillGraphService.setEmployeeSkill(employeeSkill);
        return Result.success(result);
    }

    @GetMapping("/employee/{employeeId}")
    public Result<List<EmployeeSkill>> getEmployeeSkills(@PathVariable Long employeeId) {
        List<EmployeeSkill> skills = skillGraphService.getEmployeeSkills(employeeId);
        return Result.success(skills);
    }

    @GetMapping("/employee/{employeeId}/evaluate")
    public Result<Map<String, Object>> evaluateEmployeeSkills(@PathVariable Long employeeId) {
        Map<String, Object> evaluation = skillGraphService.evaluateEmployeeSkills(employeeId);
        return Result.success(evaluation);
    }

    @GetMapping("/employee/{employeeId}/learning-path")
    public Result<List<Map<String, Object>>> recommendLearningPath(
            @PathVariable Long employeeId,
            @RequestParam String targetRole) {
        List<Map<String, Object>> path = skillGraphService.recommendLearningPath(employeeId, targetRole);
        return Result.success(path);
    }

    @PostMapping("/find-employees")
    public Result<List<Map<String, Object>>> findEmployeesBySkills(
            @RequestBody List<String> requiredSkills,
            @RequestParam(required = false, defaultValue = "3") Integer minProficiency) {
        List<Map<String, Object>> employees = skillGraphService.findEmployeesBySkills(requiredSkills, minProficiency);
        return Result.success(employees);
    }
}
