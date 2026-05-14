package com.supplychain.supplier.controller;

import com.supplychain.common.dto.ResponseResult;
import com.supplychain.common.entity.SupplierEvaluation;
import com.supplychain.supplier.service.SupplierEvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "供应商评估", description = "供应商评估管理接口")
@RestController
@RequestMapping("/api/suppliers/evaluations")
@RequiredArgsConstructor
public class SupplierEvaluationController {

    private final SupplierEvaluationService evaluationService;

    @Operation(summary = "创建供应商评估")
    @PostMapping
    public ResponseResult<SupplierEvaluation> createEvaluation(
            @RequestBody SupplierEvaluation evaluation) {
        return ResponseResult.success(evaluationService.createEvaluation(evaluation));
    }

    @Operation(summary = "获取供应商评估列表")
    @GetMapping("/supplier/{supplierId}")
    public ResponseResult<List<SupplierEvaluation>> getEvaluationsBySupplier(
            @PathVariable String supplierId) {
        return ResponseResult.success(evaluationService.getEvaluationsBySupplier(supplierId));
    }

    @Operation(summary = "获取评估详情")
    @GetMapping("/{evaluationId}")
    public ResponseResult<SupplierEvaluation> getEvaluation(@PathVariable String evaluationId) {
        return ResponseResult.success(evaluationService.getEvaluation(evaluationId));
    }
}
