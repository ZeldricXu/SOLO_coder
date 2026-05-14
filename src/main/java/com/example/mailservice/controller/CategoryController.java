package com.example.mailservice.controller;

import com.example.mailservice.dto.ApiResponse;
import com.example.mailservice.model.CategoryRule;
import com.example.mailservice.service.ArchiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/mail/category")
@RequiredArgsConstructor
public class CategoryController {

    private final ArchiveService archiveService;

    @PostMapping
    public ApiResponse<Map<String, Object>> createCategoryRule(@RequestBody CategoryRuleRequest request) {
        log.info("创建分类规则: {}", request);

        CategoryRule rule = CategoryRule.builder()
                .ruleName(request.getRuleName())
                .rulePattern(request.getRulePattern())
                .targetCategory(request.getTargetCategory() != null ? request.getTargetCategory() :
                        request.getRuleName().replace("\\s+", "_").toLowerCase())
                .rulePriority(request.getRulePriority() != null ? request.getRulePriority() : 0)
                .enabled(true)
                .build();

        CategoryRule saved = archiveService.createCategoryRule(rule);

        Map<String, Object> data = new HashMap<>();
        data.put("rule_id", saved.getRuleId());
        data.put("rule_name", saved.getRuleName());
        data.put("target_category", saved.getTargetCategory());

        return ApiResponse.success(data);
    }

    @GetMapping
    public ApiResponse<List<CategoryRule>> getAllRules() {
        return ApiResponse.success(archiveService.getActiveRules());
    }

    @PutMapping("/{ruleId}")
    public ApiResponse<CategoryRule> updateRule(
            @PathVariable String ruleId,
            @RequestBody CategoryRuleRequest request) {

        CategoryRule updated = CategoryRule.builder()
                .ruleName(request.getRuleName())
                .rulePattern(request.getRulePattern())
                .targetCategory(request.getTargetCategory())
                .rulePriority(request.getRulePriority())
                .enabled(request.getEnabled())
                .build();

        CategoryRule result = archiveService.updateCategoryRule(ruleId, updated);
        if (result == null) {
            return ApiResponse.error(404, "规则不存在");
        }
        return ApiResponse.success(result);
    }

    @DeleteMapping("/{ruleId}")
    public ApiResponse<String> deleteRule(@PathVariable String ruleId) {
        archiveService.deleteCategoryRule(ruleId);
        return ApiResponse.success(null, "规则删除成功");
    }

    @PostMapping("/manual/{mailId}")
    public ApiResponse<String> manualCategorize(
            @PathVariable String mailId,
            @RequestParam String category) {

        archiveService.manualCategorize(mailId, category);
        return ApiResponse.success(null, "分类成功");
    }

    @lombok.Data
    public static class CategoryRuleRequest {
        private String ruleName;
        private String rulePattern;
        private String targetCategory;
        private Integer rulePriority;
        private Boolean enabled;
    }
}
