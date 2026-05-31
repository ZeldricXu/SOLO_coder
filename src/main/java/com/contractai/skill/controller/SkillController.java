package com.contractai.skill.controller;

import com.contractai.common.dto.PageQuery;
import com.contractai.common.dto.PageResult;
import com.contractai.common.result.ApiResponse;
import com.contractai.skill.dto.*;
import com.contractai.skill.entity.*;
import com.contractai.skill.service.SkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    @PostMapping("/categories")
    public ApiResponse<SkillCategory> createCategory(@RequestBody SkillCategoryCreateDTO dto) {
        return ApiResponse.created(skillService.createCategory(dto));
    }

    @GetMapping("/categories")
    public ApiResponse<List<SkillCategory>> listCategories() {
        return ApiResponse.success(skillService.listCategories());
    }

    @GetMapping("/categories/tree")
    public ApiResponse<List<SkillCategory>> getCategoryTree() {
        return ApiResponse.success(skillService.getCategoryTree());
    }

    @PostMapping
    public ApiResponse<Skill> createSkill(@RequestBody SkillCreateDTO dto) {
        return ApiResponse.created(skillService.createSkill(dto));
    }

    @GetMapping
    public ApiResponse<PageResult<Skill>> listSkills(@ModelAttribute PageQuery query) {
        return ApiResponse.success(skillService.listSkills(query));
    }

    @GetMapping("/{id}")
    public ApiResponse<Skill> getSkill(@PathVariable Long id) {
        return ApiResponse.success(skillService.getSkill(id));
    }

    @PostMapping("/employees")
    public ApiResponse<Employee> createEmployee(@RequestBody EmployeeCreateDTO dto) {
        return ApiResponse.created(skillService.createEmployee(dto));
    }

    @GetMapping("/employees")
    public ApiResponse<PageResult<Employee>> listEmployees(@ModelAttribute PageQuery query) {
        return ApiResponse.success(skillService.listEmployees(query));
    }

    @PostMapping("/employee-skills")
    public ApiResponse<EmployeeSkill> createEmployeeSkill(@RequestBody EmployeeSkillCreateDTO dto) {
        return ApiResponse.created(skillService.createEmployeeSkill(dto));
    }

    @GetMapping("/employees/{employeeId}/skills")
    public ApiResponse<List<EmployeeSkill>> getEmployeeSkills(@PathVariable Long employeeId) {
        return ApiResponse.success(skillService.getEmployeeSkills(employeeId));
    }

    @PostMapping("/assessments")
    public ApiResponse<EmployeeSkill> assessSkill(@RequestBody SkillAssessmentDTO dto) {
        return ApiResponse.success(skillService.assessSkill(dto));
    }

    @GetMapping("/employees/{employeeId}/skill-matrix")
    public ApiResponse<Map<String, Object>> getEmployeeSkillMatrix(@PathVariable Long employeeId) {
        return ApiResponse.success(skillService.getEmployeeSkillMatrix(employeeId));
    }

    @PostMapping("/learning-paths")
    public ApiResponse<LearningPath> createLearningPath(@RequestBody LearningPathCreateDTO dto) {
        return ApiResponse.created(skillService.createLearningPath(dto));
    }

    @PostMapping("/learning-paths/recommend")
    public ApiResponse<List<LearningPath>> recommendLearningPaths(@RequestBody LearningRecommendationDTO dto) {
        return ApiResponse.success(skillService.recommendLearningPaths(dto));
    }

    @PostMapping("/match")
    public ApiResponse<List<Map<String, Object>>> findEmployeesBySkills(
            @RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Long> skillIds = (List<Long>) request.get("skillIds");
        BigDecimal minScore = request.get("minMatchScore") != null ?
                new BigDecimal(request.get("minMatchScore").toString()) : null;
        return ApiResponse.success(skillService.findEmployeesBySkills(skillIds, minScore));
    }
}
