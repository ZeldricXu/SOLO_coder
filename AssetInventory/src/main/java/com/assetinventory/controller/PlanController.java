package com.assetinventory.controller;

import com.assetinventory.dto.ApiResponse;
import com.assetinventory.entity.InventoryPlan;
import com.assetinventory.service.PlanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final PlanService planService;

    @Autowired
    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<InventoryPlan>> createPlan(@RequestBody InventoryPlan plan) {
        InventoryPlan created = planService.createPlan(
                plan.getPlanName(),
                plan.getPlanRange(),
                plan.getPlanStart(),
                plan.getPlanEnd()
        );
        return ResponseEntity.ok(ApiResponse.success(created));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<InventoryPlan>>> getAllPlans() {
        List<InventoryPlan> plans = planService.getAllPlans();
        return ResponseEntity.ok(ApiResponse.success(plans));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<InventoryPlan>>> getActivePlans() {
        List<InventoryPlan> plans = planService.getActivePlans();
        return ResponseEntity.ok(ApiResponse.success(plans));
    }

    @GetMapping("/{planId}")
    public ResponseEntity<ApiResponse<InventoryPlan>> getPlanById(@PathVariable String planId) {
        return planService.getPlanById(planId)
                .map(plan -> ResponseEntity.ok(ApiResponse.success(plan)))
                .orElse(ResponseEntity.ok(ApiResponse.error(404, "盘点计划不存在")));
    }
}
