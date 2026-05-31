package com.datamasker.interfaces.controller;

import com.datamasker.application.service.MaskingService;
import com.datamasker.domain.masking.model.MaskingResult;
import com.datamasker.domain.masking.model.MaskingRule;
import com.datamasker.interfaces.dto.Result;
import com.datamasker.interfaces.dto.masking.AddRuleRequest;
import com.datamasker.interfaces.dto.masking.MaskDataRequest;
import com.datamasker.interfaces.dto.masking.MaskDataResponse;
import com.datamasker.interfaces.dto.masking.RuleResponse;
import com.datamasker.interfaces.dto.masking.UpdateRuleRequest;
import com.datamasker.interfaces.assembler.MaskingAssembler;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/masking")
@RequiredArgsConstructor
public class MaskingController {

    private final MaskingService maskingService;

    @PostMapping("/apply")
    public Result<MaskDataResponse> maskData(@Valid @RequestBody MaskDataRequest request) {
        List<MaskingResult> results = maskingService.maskData(
                request.getUserLevel(), request.getFields(), request.getFieldCategories());
        MaskDataResponse response = MaskingAssembler.toMaskDataResponse(results);
        return Result.success(response);
    }

    @PostMapping("/rules")
    public Result<RuleResponse> addRule(@Valid @RequestBody AddRuleRequest request) {
        MaskingRule rule = maskingService.addRule(
                request.getFieldPattern(), request.getStrategy(),
                request.getLevelRequired(), request.getParams());
        RuleResponse response = MaskingAssembler.toRuleResponse(rule);
        return Result.success(response);
    }

    @GetMapping("/rules")
    public Result<List<RuleResponse>> getRules() {
        List<MaskingRule> rules = maskingService.getRules();
        List<RuleResponse> responses = rules.stream().map(MaskingAssembler::toRuleResponse).toList();
        return Result.success(responses);
    }

    @PutMapping("/rules/{ruleId}")
    public Result<RuleResponse> updateRule(@PathVariable String ruleId,
                                           @RequestBody UpdateRuleRequest request) {
        MaskingRule rule = maskingService.updateRule(
                ruleId, request.getStrategy(), request.getLevelRequired(),
                request.getParams(), request.isEnabled());
        RuleResponse response = MaskingAssembler.toRuleResponse(rule);
        return Result.success(response);
    }

    @DeleteMapping("/rules/{ruleId}")
    public Result<Void> deleteRule(@PathVariable String ruleId) {
        maskingService.deleteRule(ruleId);
        return Result.success(null);
    }
}
