package com.cdcsync.quality.controller;

import com.cdcsync.common.api.PageResult;
import com.cdcsync.common.api.Result;
import com.cdcsync.quality.domain.QualityCheckResult;
import com.cdcsync.quality.domain.QualityRule;
import com.cdcsync.quality.service.QualityCheckResultService;
import com.cdcsync.quality.service.QualityRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/quality-rules")
@RequiredArgsConstructor
public class QualityRuleController {

    private final QualityRuleService qualityRuleService;
    private final QualityCheckResultService qualityCheckResultService;

    @PostMapping
    public Result<QualityRule> create(@Valid @RequestBody QualityRule rule) {
        return Result.success(qualityRuleService.create(rule));
    }

    @PutMapping("/{id}")
    public Result<QualityRule> update(@PathVariable String id, @Valid @RequestBody QualityRule rule) {
        rule.setId(id);
        return Result.success(qualityRuleService.update(rule));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        qualityRuleService.delete(id);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<QualityRule> getById(@PathVariable String id) {
        return Result.success(qualityRuleService.findById(id));
    }

    @GetMapping
    public Result<PageResult<QualityRule>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(qualityRuleService.findPage(pageNum, pageSize));
    }

    @PostMapping("/{id}/execute")
    public Result<QualityCheckResult> executeRule(@PathVariable String id) {
        return Result.success(qualityRuleService.executeRule(id));
    }

    @PostMapping("/execute-all")
    public Result<List<QualityCheckResult>> executeAllRules() {
        return Result.success(qualityRuleService.executeAllRules());
    }

    @PostMapping("/{id}/enable")
    public Result<Void> enableRule(@PathVariable String id) {
        qualityRuleService.enableRule(id);
        return Result.success();
    }

    @PostMapping("/{id}/disable")
    public Result<Void> disableRule(@PathVariable String id) {
        qualityRuleService.disableRule(id);
        return Result.success();
    }

    @GetMapping("/{id}/results")
    public Result<PageResult<QualityCheckResult>> getResults(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(qualityCheckResultService.findByRuleId(id, pageNum, pageSize));
    }

    @GetMapping("/{id}/results/all")
    public Result<List<QualityCheckResult>> getAllResults(@PathVariable String id) {
        return Result.success(qualityCheckResultService.findByRuleId(id));
    }
}
