package com.projmanage.controller;

import com.projmanage.dto.ApiResponse;
import com.projmanage.model.Risk;
import com.projmanage.service.RiskService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/risks")
public class RiskController {

    private final RiskService riskService;

    public RiskController(RiskService riskService) {
        this.riskService = riskService;
    }

    @PostMapping
    public ApiResponse<Risk> createRisk(@RequestParam String projectId,
                                            @RequestParam(required = false) String taskId,
                                            @RequestParam String riskType,
                                            @RequestParam String riskDescription,
                                            @RequestParam String riskLevel) {
        Risk risk = riskService.createRisk(projectId, taskId, riskType, riskDescription, riskLevel);
        return ApiResponse.success(risk);
    }

    @GetMapping("/{riskId}")
    public ApiResponse<Risk> getRiskById(@PathVariable String riskId) {
        Optional<Risk> riskOpt = riskService.getRiskById(riskId);
        if (riskOpt.isPresent()) {
            return ApiResponse.success(riskOpt.get());
        }
        return ApiResponse.error(404, "风险记录不存在");
    }

    @GetMapping("/project/{projectId}")
    public ApiResponse<List<Risk>> getRisksByProject(@PathVariable String projectId) {
        return ApiResponse.success(riskService.getRisksByProject(projectId));
    }

    @GetMapping("/project/{projectId}/active")
    public ApiResponse<List<Risk>> getActiveRisks(@PathVariable String projectId) {
        return ApiResponse.success(riskService.getActiveRisksByProject(projectId));
    }

    @GetMapping("/task/{taskId}")
    public ApiResponse<List<Risk>> getRisksByTask(@PathVariable String taskId) {
        return ApiResponse.success(riskService.getRisksByTask(taskId));
    }

    @PutMapping("/{riskId}/status")
    public ApiResponse<Void> updateRiskStatus(@PathVariable String riskId, @RequestParam String status) {
        riskService.updateRiskStatus(riskId, status);
        return ApiResponse.success(null);
    }
}
