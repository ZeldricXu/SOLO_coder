package com.fitnesscenter.controller;

import com.fitnesscenter.dto.ApiResponse;
import com.fitnesscenter.dto.PlanRequest;
import com.fitnesscenter.dto.PlanResponse;
import com.fitnesscenter.model.Plan;
import com.fitnesscenter.service.PlanService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @PostMapping("/create")
    public ApiResponse<Plan> createPlan(@RequestBody PlanRequest request) {
        Plan plan = planService.createPlan(request);
        return ApiResponse.success(plan);
    }

    @GetMapping("/query")
    public ApiResponse<PlanResponse> queryPlan(@RequestParam String memberId) {
        PlanResponse response = planService.queryPlan(memberId);
        return ApiResponse.success(response);
    }

    @GetMapping("/{planId}")
    public ApiResponse<Plan> getPlanById(@PathVariable String planId) {
        Plan plan = planService.getPlanById(planId);
        return ApiResponse.success(plan);
    }

    @GetMapping("/member/{memberId}")
    public ApiResponse<Plan> getPlanByMemberId(@PathVariable String memberId) {
        Plan plan = planService.getPlanByMemberId(memberId);
        return ApiResponse.success(plan);
    }

    @GetMapping
    public ApiResponse<List<Plan>> getAllPlans() {
        List<Plan> plans = planService.getAllPlans();
        return ApiResponse.success(plans);
    }

    @PutMapping("/{planId}/status")
    public ApiResponse<Plan> updatePlanStatus(@PathVariable String planId, @RequestParam String status) {
        Plan plan = planService.updatePlanStatus(planId, status);
        return ApiResponse.success(plan);
    }
}
