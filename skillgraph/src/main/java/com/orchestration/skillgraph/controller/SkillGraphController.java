package com.orchestration.skillgraph.controller;

import com.orchestration.common.api.ApiConstants;
import com.orchestration.common.base.Result;
import com.orchestration.persistence.entity.*;
import com.orchestration.skillgraph.service.SkillGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.API_V1_PREFIX + "/skillgraph")
@RequiredArgsConstructor
public class SkillGraphController {

    private final SkillGraphService skillGraphService;

    @PostMapping("/categories")
    public Result<Long> createCategory(@RequestBody SkillCategory category) {
        return Result.success(skillGraphService.createCategory(category));
    }

    @GetMapping("/categories")
    public Result<List<SkillCategory>> listCategories() {
        return Result.success(skillGraphService.listCategories());
    }

    @PostMapping("/skills")
    public Result<Long> createSkill(@RequestBody SkillDefinition skill) {
        return Result.success(skillGraphService.createSkill(skill));
    }

    @GetMapping("/skills/{id}")
    public Result<SkillDefinition> getSkill(@PathVariable Long id) {
        return Result.success(skillGraphService.getSkill(id));
    }

    @GetMapping("/skills")
    public Result<List<SkillDefinition>> listSkills(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(skillGraphService.listSkills(categoryId, page, size));
    }

    @PostMapping("/relations")
    public Result<Boolean> addSkillRelation(@RequestBody SkillRelation relation) {
        return Result.success(skillGraphService.addSkillRelation(relation));
    }

    @PostMapping("/learning-paths")
    public Result<Long> createLearningPath(@RequestBody LearningPath path) {
        return Result.success(skillGraphService.createLearningPath(path));
    }

    @GetMapping("/learning-paths")
    public Result<List<LearningPath>> listLearningPaths() {
        return Result.success(skillGraphService.listLearningPaths());
    }

    @PostMapping("/employee-skills/evaluate")
    public Result<Boolean> evaluateEmployeeSkill(@RequestBody EmployeeSkill employeeSkill) {
        return Result.success(skillGraphService.evaluateEmployeeSkill(employeeSkill));
    }

    @GetMapping("/employees/{employeeId}/skills")
    public Result<List<EmployeeSkill>> getEmployeeSkills(@PathVariable Long employeeId) {
        return Result.success(skillGraphService.getEmployeeSkills(employeeId));
    }

    @GetMapping("/employees/{employeeId}/recommend-path")
    public Result<List<Map<String, Object>>> recommendLearningPath(
            @PathVariable Long employeeId,
            @RequestParam Long targetSkillId) {
        return Result.success(skillGraphService.recommendLearningPath(employeeId, targetSkillId));
    }

    @GetMapping("/tree")
    public Result<Map<String, Object>> getSkillTree() {
        return Result.success(skillGraphService.getSkillTree());
    }

    @GetMapping("/employees/{employeeId}/skill-gap")
    public Result<Map<String, Object>> getEmployeeSkillGap(
            @PathVariable Long employeeId,
            @RequestParam(required = false) Long targetRoleId) {
        return Result.success(skillGraphService.getEmployeeSkillGap(employeeId, targetRoleId));
    }
}
